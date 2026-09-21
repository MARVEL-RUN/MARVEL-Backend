package kr.co.teambrain.marvelrun.admin.capacity.query.dto;

import java.util.List;

/** 참가자 목록과 페이지 정보를 제공하며 내부 예약 정보를 노출하지 않는다. */
public record CapacityParticipantPageResponse(
        List<CapacityParticipantResponse> content,
        int page, int size, long totalElements, long totalPages
) { }
