package kr.co.teambrain.marvelrun.admin.capacity.command.application.service;

import kr.co.teambrain.marvelrun.admin.capacity.command.application.domain.Capacity;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.dto.CapacityShortage;
import kr.co.teambrain.marvelrun.admin.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/*
 * 사용자 서버 참조 시각: 2026-10-02 17:18:56 KST
 * 참조 파일: user/src/main/java/kr/co/teambrain/marvelrun/user/capacity/command/application/service/RegistrationCapacityService.java
 * 유지한 동작: 신청 생성 전에 대회를 잠그고 자원 처리를 호출자 트랜잭션에 참여시킨다.
 * 관리자 적용 차이: 온라인 기간 검증 없이 모든 부족 자원을 반환한다.
 */
/** 관리자 신청의 대회 잠금과 자원 부족 확인을 담당하며 엑셀 행을 알지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RegistrationCapacityService {
    private final EventCommandRepository events;
    private final CapacityCommandRepository capacities;

    /** 사용자 신규 신청과 동일하게 대회 행을 가장 먼저 잠근다. */
    public Event lockRegistrationEvent(String eventId) {
        return events.findByIdForUpdate(eventId).orElseThrow(() -> new CustomException(ErrorCode.EVENT_NOT_FOUND));
    }

    /** 사용자 신규 신청과 동일하게 전체 정원 도달 시 접수를 마감한다. */
    public void closeRegistrationIfCapacityFull(Event event) {
        if (capacities.countFullTotalCapacities(event.getId(),
                kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.CapacityType.EVENT_TOTAL) > 0) {
            event.closeRegistrationForCapacity();
        }
    }

    /** 전체 필요량을 자원 순서대로 비교하며 최종 단계에서는 동일 순서로 잠근다. */
    public List<CapacityShortage> findRegistrationCapacityShortages(String eventId,
            List<Map<String, Integer>> requirements, boolean lock) {
        Map<String, Integer> totals = new TreeMap<>();
        requirements.forEach(values -> values.forEach((id, count) -> totals.merge(id, count, Math::addExact)));
        if (totals.isEmpty()) { return List.of(); }

        // 읽기 검증과 최종 잠금 검증은 같은 계산을 사용한다.
        List<Capacity> rows = lock ? capacities.findAllForUpdate(eventId, totals.keySet())
                : capacities.findAllById(totals.keySet());
        Map<String, Capacity> byId = new HashMap<>();
        rows.forEach(row -> byId.put(row.getId(), row));
        List<CapacityShortage> shortages = new ArrayList<>();
        totals.forEach((id, required) -> {
            Capacity capacity = byId.get(id);
            if (capacity == null || !eventId.equals(capacity.getEvent().getId())) {
                throw new CustomException(ErrorCode.CAPACITY_CONFIGURATION_ERROR);
            }
            int available = capacity.isActive()
                    ? Math.max(0, capacity.getLimitCount() - capacity.getHeldCount() - capacity.getConfirmedCount()) : 0;
            if (required > available) { shortages.add(new CapacityShortage(id, required, available)); }
        });
        return List.copyOf(shortages);
    }
}
