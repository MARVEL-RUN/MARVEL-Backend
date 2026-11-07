package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityRequirementInput;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationItem;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationEntryCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 상품 신청의 구매 줄로 정원 필요량을 구한다. 신청 때 확보(RegistrationCapacityService)와 같은 규칙이다.
 *
 * 전체 정원은 줄의 참가 항목 수(사람 수)만큼, 그 밖의 정원·기념품은 줄마다 1개를 쓴다.
 * 상품은 연령 구분이 없어 어린이 정원을 쓰지 않는다. 줄마다 구한 값은 정원 행 id별로 더해
 * 예약 상세 한 줄에 맞춘다(uk_reservation_item_capacity).
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RegistrationItemCapacityRequirements {

    private final CapacityRequirementResolver requirementResolver;
    private final RegistrationEntryCommandRepository entryRepository;

    /** 구매 줄 하나의 정원 입력. */
    public static CapacityRequirementInput input(RegistrationItem item, int participants) {
        return new CapacityRequirementInput(
                item.getEventCategory().getId(),
                false,
                item.getSouvenirJson() == null ? List.of() : item.getSouvenirJson(),
                participants
        );
    }

    /** 신청의 구매 줄 id별 참가 항목 수. 취소한 줄의 항목도 세지만 호출자는 살아 있는 줄만 읽는다. */
    public Map<String, Integer> participantsByItem(String registrationId) {
        Map<String, Integer> result = new HashMap<>();
        for (RegistrationEntry entry : entryRepository.findByRegistration_Id(registrationId)) {
            result.merge(entry.getRegistrationItem().getId(), 1, Integer::sum);
        }
        return result;
    }

    /** 줄 목록의 입력. 참가 항목이 없는 줄은 정원을 정할 수 없어 설정 오류다. */
    public static List<CapacityRequirementInput> inputs(List<RegistrationItem> items, Map<String, Integer> participantsByItem) {
        return items.stream().map(item -> {
            Integer participants = participantsByItem.get(item.getId());
            if (participants == null || participants < 1) {
                throw new CustomException(ErrorCode.CAPACITY_CONFIGURATION_ERROR,
                        " 구매 줄에 참가 항목이 없습니다. itemId=" + item.getId());
            }
            return input(item, participants);
        }).toList();
    }

    /** 입력마다 필요량을 구해 정원 행 id별로 더한다. Capacity ID 순서를 유지한다. */
    public Map<String, Integer> resolveMerged(String eventId, List<CapacityRequirementInput> inputs) {
        if (inputs.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_RESERVATION_ARGUMENT, " 정원 필요량 입력이 비어 있습니다.");
        }
        Map<String, Integer> merged = new TreeMap<>();
        for (Map<String, Integer> quantities : requirementResolver.resolveAll(eventId, inputs)) {
            quantities.forEach((capacityId, quantity) -> merged.merge(capacityId, quantity, Integer::sum));
        }
        return merged;
    }
}
