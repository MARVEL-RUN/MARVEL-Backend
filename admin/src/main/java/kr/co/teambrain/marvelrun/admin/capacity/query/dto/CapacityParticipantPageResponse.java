package kr.co.teambrain.marvelrun.admin.capacity.query.dto;

import java.util.List;

/**
 * 조회한 정원과 상태, 해당 참가자 목록 및 페이지 정보를 반환한다.
 */
public record CapacityParticipantPageResponse(
        String capacityId,
        CapacityParticipantState state,
        List<CapacityParticipantResponse> content,
        int page,
        int size,
        long totalElements,
        long totalPages
) {
}