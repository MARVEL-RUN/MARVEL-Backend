package kr.co.teambrain.marvelrun.admin.capacity.command.application.service;

import kr.co.teambrain.marvelrun.admin.capacity.command.application.dto.CapacityRequirementInput;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.dto.CapacityShortage;
import kr.co.teambrain.marvelrun.admin.event.command.application.context.OfflineRegistrationContext;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.OfflineRegistrationImportResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;

/** 공통 자원 부족 결과를 엑셀 행에 연결하며 카운터나 예약을 변경하지 않는다. */
@Service
@RequiredArgsConstructor
public class OfflineRegistrationCapacityService {
    private final CapacityRequirementResolver resolver;
    private final RegistrationCapacityService registrationCapacityService;

    /** 전체 참가자 필요량을 비교하고 부족 자원을 사용하는 모든 행에 오류를 추가한다. */
    @Transactional
    public List<Map<String, Integer>> collectOfflineCapacityErrors(String eventId,
            List<OfflineRegistrationContext> contexts, OfflineRegistrationImportResult result, boolean lock) {
        // 확정된 Context의 어린이 기준으로 기존 자원 계산기를 사용한다.
        List<CapacityRequirementInput> inputs = contexts.stream().map(context ->
                new CapacityRequirementInput(context.categoryId(), context.child(), context.souvenirs())).toList();
        List<Map<String, Integer>> requirements = resolver.resolveAll(eventId, inputs);
        List<CapacityShortage> shortages = registrationCapacityService
                .findRegistrationCapacityShortages(eventId, requirements, lock);

        // 부족 자원을 요구하는 행을 빠짐없이 표시한다.
        for (CapacityShortage shortage : shortages) {
            for (int index = 0; index < contexts.size(); index++) {
                if (requirements.get(index).containsKey(shortage.capacityId())) {
                    result.addError(contexts.get(index).rowNumber(), "capacity", "CAPACITY_EXCEEDED",
                            "자원 " + shortage.capacityId() + ": 필요 " + shortage.required() + ", 잔여 " + shortage.available());
                }
            }
        }
        return requirements;
    }
}
