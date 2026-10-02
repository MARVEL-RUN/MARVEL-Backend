package kr.co.teambrain.marvelrun.admin.capacity.command.application.service;

import kr.co.teambrain.marvelrun.admin.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.domain.ReservationItem;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.dto.CapacityHoldRequest;
import kr.co.teambrain.marvelrun.admin.capacity.command.repository.*;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.common.json_object.ReservationHistoryEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

/*
 * 사용자 서버 참조 시각: 2026-10-02 17:18:56 KST
 * 참조 파일: user/src/main/java/kr/co/teambrain/marvelrun/user/capacity/command/application/service/CapacityHoldService.java
 * 유지한 동작: 자원 ID 순서 조건부 확보와 HELD 예약·상세·HOLD 이력을 같은 트랜잭션에 저장한다.
 * 관리자 적용 차이: 파일 전체에서 검증한 필요량을 전달받으며 재확보 기능은 포함하지 않는다.
 */
/** 검증된 필요량을 확보하고 참가자별 최초 예약을 생성한다. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class CapacityHoldService {
    private final CapacityCommandRepository capacities;
    private final ReservationCommandRepository reservations;
    private final ReservationItemCommandRepository items;

    /** 원자적 수량 확보에 실패하면 호출 트랜잭션 전체를 취소한다. */
    public List<Reservation> holdRegistrationCapacities(String eventId, List<CapacityHoldRequest> requests,
            List<Map<String, Integer>> requirements, LocalDateTime now) {
        // 요청과 필요량의 대응을 확인한 후 자원 순서대로 확보한다.
        if (requests.isEmpty() || requests.size() != requirements.size()) {
            throw new CustomException(ErrorCode.INVALID_RESERVATION_ARGUMENT);
        }
        Map<String, Integer> totals = new TreeMap<>();
        requirements.forEach(values -> values.forEach((id, count) -> totals.merge(id, count, Math::addExact)));
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            if (capacities.acquireHeld(eventId, entry.getKey(), entry.getValue(), now) != 1) {
                throw new CustomException(ErrorCode.CONCURRENT_MODIFICATION);
            }
        }

        // 확보한 수량과 정확히 대응하는 예약 상세와 이력을 생성한다.
        List<Reservation> created = new ArrayList<>();
        for (int index = 0; index < requests.size(); index++) {
            Reservation reservation = reservations.save(Reservation.createHeld(requests.get(index).registration()));
            List<ReservationHistoryEntry.Item> history = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : new TreeMap<>(requirements.get(index)).entrySet()) {
                items.save(ReservationItem.create(reservation, capacities.getReferenceById(entry.getKey()), entry.getValue()));
                history.add(new ReservationHistoryEntry.Item(entry.getKey(), entry.getValue()));
            }
            reservation.appendHistory(ReservationHistoryEntry.Action.HOLD, now, null, "관리자 신규 신청 자원 확보", history);
            created.add(reservation);
        }
        return created;
    }
}
