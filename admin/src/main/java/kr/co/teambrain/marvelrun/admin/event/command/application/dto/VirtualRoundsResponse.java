package kr.co.teambrain.marvelrun.admin.event.command.application.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 버추얼런 차수 목록이다. closed는 조회 시각이 마감 시각 이후인지 나타낸다. */
public record VirtualRoundsResponse(List<Round> rounds) {
    /** 차수 번호는 시작 시각 순서(1부터)다. */
    public record Round(int number, LocalDateTime startAt, LocalDateTime deadline, boolean closed) { }
}
