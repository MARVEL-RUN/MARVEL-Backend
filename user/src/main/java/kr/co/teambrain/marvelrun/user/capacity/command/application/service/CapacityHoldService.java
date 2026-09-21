package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.CapacityType;
import kr.co.teambrain.marvelrun.common.json_object.ReservationHistoryEntry;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.ReservationItem;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityHoldRequest;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityRequirementInput;
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

    private final CapacityRequirementResolver capacityRequirementResolver;

    // 참고 : 재확보(신규 확보) == 미결제된 상태의 신청이 점유하고있는 임시 홀딩 Capacity, Reservation, ReservationItem
    // 내역을 취소하거나(reservationItem) 홀딩 카운트 해제한 뒤(capacity) 새 내역으로 확보하는 것을 의미한다.
    // '재' 확보라고 해서 동일 내역을 다시 임시홀딩 하겠다는게 아님. 새 내역을 홀딩하는것도 동일하다.


    /**
     * 신규 신청들의 자원을 확보하고 예약과 최초 확보 이력을 저장한다.
     *
     * 호출자는 정책 검증과 Registration 저장을 먼저 수행해야 한다.
     * 개인은 한 명, 단체는 구성원 전체를 전달한다.
     */
    public void holdAll(
            String eventId,
            List<CapacityHoldRequest> requests,
            LocalDateTime now
    ) {

        executeHold(
                eventId,
                requests,
                now,
                false
        );
    }

    /**
     * 반환된 예약들의 자원을 다시 확보하고 현재 확보 상세를 교체한다.
     *
     * 호출자는 재확보 가능 여부와 신청 정책을 먼저 검증해야 한다.
     * 기존 Reservation 행과 JSON 이력은 유지한다.
     *
     * 대상 중 하나라도 반환 상태가 아니거나 수량이 부족하면 전체를 롤백한다.
     * HELD 예약의 단순 재결제에는 이 메서드를 호출하지 않는다.
     */
    public void reacquireAll(
            String eventId,
            List<CapacityHoldRequest> requests,
            LocalDateTime now
    ) {

        executeHold(
                eventId,
                requests,
                now,
                true
        );
    }


    /**
     * 신규 확보 또는 반환된 예약의 재확보를 처리한다.
     *
     * 필요 수량 산출과 Capacity별 합산, 원자적 UPDATE는 공통으로 사용한다.
     * 신규 확보는 예약을 생성하고, 재확보는 기존 예약의 상세를 교체한다.
     *
     * 모든 변경은 호출자의 동일 트랜잭션에서 수행한다.
     *
     *
     * @param eventId 신청 대상 대회
     * @param requests 저장된 신청과 서버의 어린이 판정값
     * @param now 호출 서비스에서 한 번 구한 현재 시각
     */
    private void executeHold(
            String eventId,
            List<CapacityHoldRequest> requests,
            LocalDateTime now,
            boolean reacquisition
    ) {
        if (requests.isEmpty()) {
            throw new CustomException(
                    ErrorCode.INVALID_RESERVATION_ARGUMENT,
                    " 자원 확보 대상 신청 목록이 비어 있습니다."
            );
        }

        /*
         * 재확보는 기존 예약의 상태와 버전을 먼저 확인한다.
         * 이후 수량 확보나 상세 저장이 실패하면 이 변경도 함께 롤백된다.
         */
        Map<String, Reservation> existingReservations =
                reacquisition
                        ? prepareReacquisition(eventId, requests)
                        : Map.of();

        List<CapacityRequirementInput> requirementInputs =
                new ArrayList<>(requests.size());

        for (CapacityHoldRequest request : requests) {
            Registration registration =
                    request.registration();

            requirementInputs.add(
                    new CapacityRequirementInput(
                            registration.getEventCategory().getId(),
                            request.child(),
                            registration.getSouvenirJson()
                    )
            );
        }

        List<Map<String, Integer>> resolvedQuantities =
                capacityRequirementResolver.resolveAll(
                        eventId,
                        requirementInputs
                );

        List<HoldPlan> plans =
                new ArrayList<>();

        Map<String, Integer> totalQuantities =
                new TreeMap<>();

        for (int index = 0; index < requests.size(); index++) {
            Registration registration =
                    requests.get(index).registration();

            Map<String, Integer> quantities =
                    resolvedQuantities.get(index);

            plans.add(
                    new HoldPlan(
                            registration,
                            quantities
                    )
            );

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

        if (reacquisition) {
            replaceReservationItems(
                    plans,
                    existingReservations,
                    now
            );
        } else {
            saveReservations(plans, now);
        }


        // 유일성 제약 위반 등을 이 메서드가 끝나기 전에 확인한다.
        reservationItemRepository.flush();
    }



    /**
     * 확보에 성공한 참가자별로 예약과 상세 내역을 저장한다.
     *
     * 최초 확보 당시의 Capacity와 수량을 JSON 이력에도 기록하여,
     * 이후 상세 내역을 교체하더라도 과거 확보 구성을 보존한다.
     *
     * Capacity는 연관관계 연결용 참조만 사용하며 카운터를 읽지 않는다.
     * 저장에 실패하면 동일 트랜잭션에서 증가시킨 수량도 함께 롤백된다.
     */
    private void saveReservations(
            List<HoldPlan> plans,
            LocalDateTime now
    ) {
        for (HoldPlan plan : plans) {

            Reservation reservation =
                    Reservation.createHeld(plan.registration());

            /*
             * 실제 확보에 사용한 Capacity와 수량을 복사한다.
             * 엔티티 자체가 아닌 식별자와 수량만 이력에 저장한다.
             */
            List<ReservationHistoryEntry.Item> historyItems =
                    plan.quantities().entrySet().stream()
                            .map(
                                    entry -> new ReservationHistoryEntry.Item(
                                            entry.getKey(),
                                            entry.getValue()
                                    )
                            )
                            .toList();

            reservation.appendHistory(
                    ReservationHistoryEntry.Action.HOLD,
                    now,
                    null,
                    "신규 신청에 따른 자원 확보",
                    historyItems
            );

            Reservation savedReservation =
                    reservationRepository.save(reservation);

            /*
             * 확정·반환 처리에 사용할 현재 확보 상세를 저장한다.
             * JSON 이력과 동일한 확보 계획을 기준으로 생성한다.
             */
            for (Map.Entry<String, Integer> entry :
                    plan.quantities().entrySet()) {

                reservationItemRepository.save(
                        ReservationItem.create(
                                savedReservation,
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
     * 재확보 대상 예약을 조회하고 RELEASED에서 HELD로 전환한다.
     *
     * 대상 중복, 예약 누락 및 대회 불일치를 확인한다.
     * 예약을 식별자 순서로 조회한 후 버전 충돌을 확인하고 수량 확보로 진행한다.
     *
     * flush는 커밋이 아니므로 이후 실패 시 상태와 회차 증가도 롤백된다.
     */
    private Map<String, Reservation> prepareReacquisition(
            String eventId,
            List<CapacityHoldRequest> requests
    ) {

        Set<String> registrationIds = new HashSet<>();

        for (CapacityHoldRequest request : requests) {

            if (!eventId.equals(
                    request.registration().getEvent().getId()
            )) {
                throw new CustomException(
                        ErrorCode.INVALID_RESERVATION_ARGUMENT,
                        " 재확보 대상 신청의 대회가 일치하지 않습니다."
                                + " registrationId="
                                + request.registration().getId()
                );
            }

            if (!registrationIds.add(request.registration().getId())) {
                throw new CustomException(
                        ErrorCode.INVALID_RESERVATION_ARGUMENT,
                        " 재확보 대상 신청이 중복되었습니다."
                                + " registrationId="
                                + request.registration().getId()
                );
            }
        }

        List<Reservation> reservations =
                reservationRepository.findAllByRegistrationIds(
                        registrationIds
                );

        if (reservations.size() != registrationIds.size()) {
            throw new CustomException(
                    ErrorCode.RESERVATION_NOT_FOUND,
                    " 재확보 대상 신청 중 예약이 누락되어 있습니다."
            );
        }

        Map<String, Reservation> result = new LinkedHashMap<>();

        for (Reservation reservation : reservations) {

            if (!eventId.equals(
                    reservation.getRegistration().getEvent().getId()
            )) {
                throw new CustomException(
                        ErrorCode.INVALID_RESERVATION_ARGUMENT,
                        " 재확보 대상 예약의 대회가 일치하지 않습니다."
                                + " reservationId=" + reservation.getId()
                );
            }

            /*
             * RELEASED만 허용한다.
             * 같은 예약을 중복 재확보하거나 결제 진행 중인 확보를 변경하지 않는다.
             */
            reservation.reacquireHeld();

            result.put(
                    reservation.getRegistration().getId(),
                    reservation
            );
        }

        /*
         * 동시 재확보 등으로 예약 버전이 변경됐다면 여기서 실패한다.
         * 아직 커밋하지 않으므로 이후 Capacity 확보 실패도 전체 롤백된다.
         */
        reservationRepository.flush();

        return result;
    }

    /**
     * 재확보에 성공한 예약의 현재 상세를 새 확보 계획으로 교체한다.
     *
     * 기존 Reservation과 이전 JSON 이력은 유지한다.
     * 이전 ReservationItem은 삭제하고 새 상세와 REHOLD 이력을 저장한다.
     *
     * 상세 저장에 실패하면 수량 확보와 예약 상태 변경도 함께 롤백된다.
     */
    private void replaceReservationItems(
            List<HoldPlan> plans,
            Map<String, Reservation> existingReservations,
            LocalDateTime now
    ) {

        List<String> reservationIds =
                existingReservations.values().stream()
                        .map(Reservation::getId)
                        .toList();

        /*
         * 현재 상세만 교체한다.
         * 이전 확보 회차의 구성은 기존 HOLD/REHOLD 이력에 남아 있다.
         */
        reservationItemRepository.deleteAllByReservationIds(
                reservationIds
        );

        for (HoldPlan plan : plans) {

            Reservation reservation =
                    existingReservations.get(
                            plan.registration().getId()
                    );

            List<ReservationHistoryEntry.Item> historyItems =
                    plan.quantities().entrySet().stream()
                            .map(
                                    entry -> new ReservationHistoryEntry.Item(
                                            entry.getKey(),
                                            entry.getValue()
                                    )
                            )
                            .toList();

            /*
             * prepareReacquisition에서 증가시킨 회차로 기록한다.
             * 실제 확보 UPDATE에 사용한 계획과 동일한 내용을 저장한다.
             */
            reservation.appendHistory(
                    ReservationHistoryEntry.Action.REHOLD,
                    now,
                    null,
                    "반환된 미결제 예약의 자원 신규 확보",
                    historyItems
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