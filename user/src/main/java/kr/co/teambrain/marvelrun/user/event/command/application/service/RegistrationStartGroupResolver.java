package kr.co.teambrain.marvelrun.user.event.command.application.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategoryStartGroup;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryStartGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 신청 쓰기 경로가 공유하는 종목별 그룹 선택 검증과 최하위 그룹 결정을 담당한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegistrationStartGroupResolver {
    private final EventCategoryStartGroupRepository repository;

    /** 요청 순서를 유지한 결과를 반환하며 참가자 수와 무관하게 활성 그룹을 한 번 조회한다. */
    public List<Selection> resolveRegistrationStartGroups(List<Input> inputs) {
        // HTTP 외 내부 생성 경로에서도 필수값과 두 입력값의 조합을 확인한다.
        if (inputs == null || inputs.stream().anyMatch(input -> input == null
                || input.eventCategoryId() == null || input.eventCategoryId().isBlank()
                || input.unselected() == null
                || (!input.unselected() && (input.startGroupId() == null || input.startGroupId().isBlank())))) {
            throw new CustomException(ErrorCode.INVALID_START_GROUP_SELECTION);
        }
        if (inputs.isEmpty()) {
            return List.of();
        }

        // 종목별 그룹과 후미를 미리 구성하여 참가자별 DB 조회를 방지한다.
        List<String> categoryIds = inputs.stream().map(Input::eventCategoryId).distinct().toList();
        List<EventCategoryStartGroup> groups = repository
                .findByEventCategoryIdInAndActiveTrueOrderByEventCategoryIdAscSortOrderAsc(categoryIds);
        Map<String, EventCategoryStartGroup> byId = new HashMap<>();
        Map<String, EventCategoryStartGroup> rearByCategory = new HashMap<>();
        for (EventCategoryStartGroup group : groups) {
            byId.put(group.getId(), group);
            EventCategoryStartGroup rear = rearByCategory.get(group.getEventCategoryId());
            if (rear == null || rear.getSortOrder() < group.getSortOrder()) {
                rearByCategory.put(group.getEventCategoryId(), group);
            }
        }

        // 미선택은 전달된 ID 대신 최하위를, 직접 선택은 해당 종목의 활성 그룹만 사용한다.
        List<Selection> selections = new ArrayList<>(inputs.size());
        for (Input input : inputs) {
            EventCategoryStartGroup rear = rearByCategory.get(input.eventCategoryId());
            if (rear == null) {
                throw new CustomException(ErrorCode.START_GROUP_CONFIGURATION_ERROR);
            }
            EventCategoryStartGroup selected = input.unselected() ? rear : byId.get(input.startGroupId());
            if (selected == null || !selected.getEventCategoryId().equals(input.eventCategoryId())) {
                throw new CustomException(ErrorCode.INVALID_START_GROUP_SELECTION);
            }
            selections.add(new Selection(selected.getId(), input.unselected()));
        }
        return List.copyOf(selections);
    }

    /** 참가자 종목과 입력값을 전달한다. 대회·참가자 소속 인증은 호출자의 책임이다. */
    public record Input(String eventCategoryId, String startGroupId, Boolean unselected) { }

    /** DB에 함께 저장할 실제 그룹 UUID와 미선택 여부다. */
    public record Selection(String startGroupId, boolean unselected) { }
}
