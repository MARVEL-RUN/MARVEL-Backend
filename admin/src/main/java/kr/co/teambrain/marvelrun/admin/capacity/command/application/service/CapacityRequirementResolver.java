package kr.co.teambrain.marvelrun.admin.capacity.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.CapacityType;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.dto.CapacityRequirementInput;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.dto.CapacityTarget;
import kr.co.teambrain.marvelrun.admin.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 참가자별 정원·기념품 필요량을 계산한다.
 *
 * 최초 확보·재확보·수정 후보가 동일한 계산 로직을 사용한다.
 * 실제 카운터 변경과 Reservation 저장은 수행하지 않는다.
 *
 * 조회 결과는 호출 내부에서만 공유하며 요청 간 캐시로 보관하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class CapacityRequirementResolver {

    private final CapacityCommandRepository capacityRepository;

    /**
     * 입력 순서에 대응하는 참가자별 Capacity 필요량을 반환한다.
     *
     * 같은 종목은 한 번 조회하고 기념품 재고 설정은 함께 조회한다.
     * 결과 Map은 Capacity ID 순서를 유지하는 변경 불가능한 복사본이다.
     */
    public List<Map<String, Integer>> resolveAll(
            String eventId,
            List<CapacityRequirementInput> inputs
    ) {
        if (inputs.isEmpty()) {
            return List.of();
        }

        // 동일 종목은 이번 호출 안에서 한 번만 조회한다.
        Map<String, List<CapacityTarget>> categoryTargets =
                new HashMap<>();

        Set<String> souvenirIds =
                new HashSet<>();

        for (CapacityRequirementInput input : inputs) {
            categoryTargets.computeIfAbsent(
                    input.eventCategoryId(),
                    categoryId -> capacityRepository.findRegistrationTargets(
                            eventId,
                            categoryId,
                            CapacityType.EVENT_TOTAL
                    )
            );

            for (SouvenirJson selection : input.souvenirs()) {
                souvenirIds.add(selection.souvenirId());
            }
        }

        Map<String, List<CapacityTarget>> souvenirTargets =
                loadSouvenirTargets(eventId, souvenirIds);

        List<Map<String, Integer>> results =
                new ArrayList<>(inputs.size());

        for (CapacityRequirementInput input : inputs) {
            Map<String, Integer> quantities =
                    calculateQuantities(
                            input,
                            categoryTargets.get(input.eventCategoryId()),
                            souvenirTargets
                    );

            results.add(
                    Collections.unmodifiableMap(
                            new TreeMap<>(quantities)
                    )
            );
        }

        return List.copyOf(results);
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
        Map<String, List<CapacityTarget>> result =
                new HashMap<>();

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
            CapacityRequirementInput input,
            List<CapacityTarget> categoryTargets,
            Map<String, List<CapacityTarget>> souvenirTargets
    ) {
        Map<String, Integer> quantities =
                new TreeMap<>();

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
                    if (input.child()) {
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

        for (SouvenirJson selection : input.souvenirs()) {
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
     * 필수 Capacity 설정이 없거나 일관되지 않은 경우의 예외를 생성한다.
     */
    private CustomException configurationError() {
        return new CustomException(
                ErrorCode.CAPACITY_CONFIGURATION_ERROR
        );
    }
}