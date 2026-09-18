package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.CapacityType;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.ReservationItem;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityHoldRequest;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityTarget;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationItemCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 개인 또는 단체 신청에 필요한 정원과 기념품을 일괄 확보한다.
 *
 * 참가자별 필요 자원을 계산하고, 동일 Capacity의 수량을 합산한 뒤
 * Capacity ID 순서로 조건부 UPDATE를 실행한다.
 *
 * 하나라도 확보하지 못하면 예외를 발생시켜 호출 트랜잭션을 롤백한다.
 * 모든 확보에 성공하면 참가자별 Reservation과 Item을 저장한다.
 *
 * 자체 트랜잭션을 시작하지 않으며, 신청 생성 서비스의
 * 트랜잭션 안에서 호출해야 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class CapacityHoldService {

    private final CapacityCommandRepository capacityRepository;
    private final ReservationCommandRepository reservationRepository;
    private final ReservationItemCommandRepository reservationItemRepository;

    /**
     * 신규 신청들의 자원을 확보하고 예약 내역을 저장한다.
     *
     * 개인 신청은 1명, 단체 신청은 구성원 전체를 한 번에 전달한다.
     * !!호출자는 정책 검증과 Registration 저장을 먼저 수행해야 한다.!!
     *
     * @param eventId 신청 대상 대회
     * @param requests 저장된 신청과 서버의 어린이 판정값
     * @param now 호출 서비스에서 한 번 구한 현재 시각
     */
    public void holdAll(
            String eventId,
            List<CapacityHoldRequest> requests,
            LocalDateTime now
    ) {
        // 동일 종목은 이번 호출 안에서 한 번만 조회한다.
        Map<String, List<CapacityTarget>> categoryTargets = new HashMap<>();
        Set<String> souvenirIds = new HashSet<>();

        for (CapacityHoldRequest request : requests) {
            Registration registration = request.registration();
            String categoryId = registration.getEventCategory().getId();

            categoryTargets.computeIfAbsent(
                    categoryId,
                    id -> capacityRepository.findRegistrationTargets(
                            eventId,
                            id,
                            CapacityType.EVENT_TOTAL
                    )
            );

            for (SouvenirJson selection : registration.getSouvenirJson()) {
                souvenirIds.add(selection.souvenirId());
            }
        }

        Map<String, List<CapacityTarget>> souvenirTargets =
                loadSouvenirTargets(eventId, souvenirIds);

        List<HoldPlan> plans = new ArrayList<>();
        Map<String, Integer> totalQuantities = new TreeMap<>();

        for (CapacityHoldRequest request : requests) {
            Registration registration = request.registration();

            Map<String, Integer> quantities = calculateQuantities(
                    request,
                    categoryTargets.get(
                            registration.getEventCategory().getId()
                    ),
                    souvenirTargets
            );

            plans.add(new HoldPlan(registration, quantities));

            quantities.forEach(
                    (capacityId, quantity) ->
                            totalQuantities.merge(
                                    capacityId,
                                    quantity,
                                    Integer::sum
                            )
            );
        }

        // TreeMap이므로 참가자 순서와 무관하게 Capacity ID 순으로 갱신한다.
        for (Map.Entry<String, Integer> entry : totalQuantities.entrySet()) {
            int updated = capacityRepository.acquireHeld(
                    eventId,
                    entry.getKey(),
                    entry.getValue(),
                    now
            );

            if (updated != 1) {
                throw new CustomException(
                        ErrorCode.CAPACITY_ACQUIRE_FAILED
                );
            }
        }

        saveReservations(plans);

        // 유일성 제약 위반 등을 이 메서드가 끝나기 전에 확인한다.
        reservationItemRepository.flush();
    }

    /**
     * 지급하는 기념품의 Capacity를 한 번에 조회한다.
     *
     * 조회 결과를 기념품 ID별로 묶어 참가자 전체가 재사용한다.
     * 비활성 Capacity도 유지하여 실제 확보 UPDATE에서 차단한다.
     */
    private Map<String, List<CapacityTarget>> loadSouvenirTargets(
            String eventId,
            Set<String> souvenirIds
    ) {
        Map<String, List<CapacityTarget>> result = new HashMap<>();

        if (souvenirIds.isEmpty()) {
            return result;
        }

        for (CapacityTarget target :
                capacityRepository.findSouvenirTargets(eventId, souvenirIds)) {

            if (target.type() != CapacityType.SOUVENIR) {
                throw new CustomException(
                        ErrorCode.CAPACITY_CONFIGURATION_ERROR,
                        " 기념품 재고의 Capacity 타입이 올바르지 않습니다."
                );
            }

            result.computeIfAbsent(
                    target.souvenirId(),
                    ignored -> new ArrayList<>()
            ).add(target);
        }

        return result;
    }

    /**
     * 참가자 한 명이 사용할 정원과 기념품 수량을 계산한다.
     *
     * 전체 정원과 종목 정원은 각각 한 개의 설정을 요구한다.
     * 어린이 정원은 설정된 경우 어린이에게만 적용한다.
     * 합산 정원은 연결된 모든 설정을 적용한다.
     */
    private Map<String, Integer> calculateQuantities(
            CapacityHoldRequest request,
            List<CapacityTarget> categoryTargets,
            Map<String, List<CapacityTarget>> souvenirTargets
    ) {
        Map<String, Integer> quantities = new TreeMap<>();

        int totalCount = 0;
        int categoryCount = 0;
        int childCategoryCount = 0;

        for (CapacityTarget target : categoryTargets) {
            switch (target.type()) {
                case EVENT_TOTAL -> {
                    totalCount++;
                    quantities.put(target.capacityId(), 1);
                }
                case CATEGORY -> {
                    categoryCount++;
                    quantities.put(target.capacityId(), 1);
                }
                case CHILD_CATEGORY -> {
                    childCategoryCount++;
                    if (request.child()) {
                        quantities.put(target.capacityId(), 1);
                    }
                }
                case CATEGORY_GROUP ->
                        quantities.put(target.capacityId(), 1);

                default -> throw configurationError();
            }
        }

        if (totalCount != 1 || categoryCount != 1 || childCategoryCount > 1) {
            throw configurationError();
        }

        for (SouvenirJson selection :
                request.registration().getSouvenirJson()) {

            addSouvenirQuantities(
                    selection,
                    souvenirTargets.getOrDefault(
                            selection.souvenirId(),
                            List.of()
                    ),
                    quantities
            );
        }

        return quantities;
    }

    /**
     * 선택한 기념품의 전체 재고와 해당 사이즈 재고를 필요 수량에 추가한다.
     *
     * size가 빈 문자열인 Capacity는 모든 사이즈에 적용한다.
     * 사이즈별 설정이 하나라도 있으면 선택한 사이즈의 설정도 필수이다.
     * 기념품 설정 전체가 없는 경우에도 설정 오류로 차단한다.
     *
     * 기존 Validator가 정규화한 selectedSize를 그대로 사용한다.
     * 사이즈 없는 기념품의 선택값 FREE는 전체 재고와 매칭할 수 있다.
     */
    private void addSouvenirQuantities(
            SouvenirJson selection,
            List<CapacityTarget> targets,
            Map<String, Integer> quantities
    ) {
        if (targets.isEmpty()) {
            throw configurationError();
        }

        boolean hasSizeSpecific = false;
        boolean matchedSize = false;
        boolean matchedAny = false;

        for (CapacityTarget target : targets) {
            if (target.size().isEmpty()) {
                quantities.put(target.capacityId(), 1);
                matchedAny = true;
                continue;
            }

            hasSizeSpecific = true;

            if (target.size().equals(selection.selectedSize())) {
                quantities.put(target.capacityId(), 1);
                matchedSize = true;
                matchedAny = true;
            }
        }

        if (!matchedAny || (hasSizeSpecific && !matchedSize)) {
            throw configurationError();
        }
    }

    /**
     * 확보에 성공한 참가자별로 예약과 상세 내역을 저장한다.
     *
     * Capacity는 연관관계 연결용 참조만 사용하며 카운터를 읽지 않는다.
     * 동일 신청의 예약이 이미 있으면 DB 유일성 제약으로 저장이 실패하고,
     * 동일 트랜잭션에서 증가시킨 카운터도 함께 롤백된다.
     */
    private void saveReservations(List<HoldPlan> plans) {
        for (HoldPlan plan : plans) {
            Reservation reservation = reservationRepository.save(
                    Reservation.createHeld(plan.registration())
            );

            for (Map.Entry<String, Integer> entry :
                    plan.quantities().entrySet()) {

                reservationItemRepository.save(
                        ReservationItem.create(
                                reservation,
                                capacityRepository.getReferenceById(
                                        entry.getKey()
                                ),
                                entry.getValue()
                        )
                );
            }
        }
    }

    /**
     * 필수 Capacity 설정이 없거나 일관되지 않은 경우의 예외를 생성한다.
     */
    private CustomException configurationError() {
        return new CustomException(
                ErrorCode.CAPACITY_CONFIGURATION_ERROR
        );
    }

    /**
     * 참가자 한 명의 예약 저장에 필요한 계산 결과이다.
     *
     * 이번 서비스 호출 내부에서만 사용하며,
     * quantities에는 Capacity별 확보 수량을 보관한다.
     */
    private record HoldPlan(
            Registration registration,
            Map<String, Integer> quantities
    ) {
    }
}