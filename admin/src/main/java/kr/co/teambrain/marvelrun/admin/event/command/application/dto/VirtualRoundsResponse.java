package kr.co.teambrain.marvelrun.admin.event.command.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/** 버추얼런 차수 목록이다. closed는 조회 시각이 마감 시각 이후인지 나타낸다. */
public record VirtualRoundsResponse(List<Round> rounds) {
    /** 차수 번호는 시작 시각 순서(1부터)다. */
    @Schema(name = "VirtualRoundResult")
    public record Round(
            @Schema(description = "차수 번호. 시작 시각 순서(1부터)") int number,
            @Schema(description = "신청 시작 시각(포함, KST)") LocalDateTime startAt,
            @Schema(description = "차수 마감 시각(제외, KST)") LocalDateTime deadline,
            @Schema(description = "조회 시각이 마감 시각 이후이면 true") boolean closed) { }
}
