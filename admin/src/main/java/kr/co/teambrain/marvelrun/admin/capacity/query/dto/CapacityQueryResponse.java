package kr.co.teambrain.marvelrun.admin.capacity.query.dto;

/** 대회의 각 정원 제한과 현재 확정·홀딩 수량을 그대로 표시한다. */
public record CapacityQueryResponse(
        String capacityId, String type, String name, String resourceKey,
        String souvenirId, String size,
        int limitCount, int confirmedCount, int heldCount, boolean active
) { }
