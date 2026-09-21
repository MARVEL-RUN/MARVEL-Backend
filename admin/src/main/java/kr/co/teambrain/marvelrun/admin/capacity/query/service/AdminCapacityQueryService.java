package kr.co.teambrain.marvelrun.admin.capacity.query.service;

import kr.co.teambrain.marvelrun.admin.capacity.query.dto.CapacityParticipantPageResponse;
import kr.co.teambrain.marvelrun.admin.capacity.query.dto.CapacityParticipantResponse;
import kr.co.teambrain.marvelrun.admin.capacity.query.dto.CapacityParticipantState;
import kr.co.teambrain.marvelrun.admin.capacity.query.dto.CapacityQueryResponse;
import kr.co.teambrain.marvelrun.admin.capacity.query.repository.AdminCapacityQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** 관리자에게 현재 정원과 점유 명단을 제공하며 금액·예약·정원을 변경하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AdminCapacityQueryService {
    private final AdminCapacityQueryRepository repository;

    /** 관리자 권한과 대회 존재를 확인한 후 전체 정원 현황을 반환한다. */
    public List<CapacityQueryResponse> capacities(String eventId) {
        requireAdmin();
        if (!repository.eventExists(eventId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "대회를 찾을 수 없습니다.");
        }
        return repository.capacities(eventId);
    }

    /** 대회 소속과 페이지 범위를 검증하고 같은 읽기 트랜잭션에서 총원·명단을 조회한다. */
    public CapacityParticipantPageResponse participants(
            String eventId, String capacityId, CapacityParticipantState state, int page, int size) {
        requireAdmin();
        if (state == null || page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "state와 페이지 범위를 확인해 주세요. size는 1~100입니다.");
        }
        if (!repository.capacityExists(eventId, capacityId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "해당 대회의 정원을 찾을 수 없습니다.");
        }
        long total = repository.participantCount(eventId, capacityId, state);
        List<CapacityParticipantResponse> content = total == 0 ? List.of()
                : repository.participants(eventId, capacityId, state, page, size);
        long totalPages = total / size + (total % size == 0 ? 0 : 1);
        return new CapacityParticipantPageResponse(
                capacityId,
                state,
                content,
                page,
                size,
                total,
                totalPages
        );
    }

    /** 메서드 보안 활성화 여부와 관계없이 DB 조회 전에 관리자 권한을 검사한다. */
    private void requireAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("관리자 권한이 필요합니다.");
        }
    }
}
