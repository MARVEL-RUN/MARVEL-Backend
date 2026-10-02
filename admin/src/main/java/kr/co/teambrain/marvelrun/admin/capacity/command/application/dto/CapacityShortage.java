package kr.co.teambrain.marvelrun.admin.capacity.command.application.dto;

/** 엑셀에 종속되지 않는 자원별 부족 수량이다. */
public record CapacityShortage(String capacityId, int required, int available) { }
