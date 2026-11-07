package kr.co.teambrain.marvelrun.admin.event.command.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

/** 버추얼런 차수 전체 목록이다. 시각은 KST이며 deadline은 차수 마감(적용 개시) 시각이다. */
public record VirtualRoundsRequest(
        @NotEmpty @Valid List<Round> rounds
) {
    /** 차수 하나의 신청 시작(포함)과 마감(제외) 시각이다. */
    @Schema(name = "VirtualRoundInput")
    public record Round(
            @Schema(description = "신청 시작 시각(포함, KST)") @NotNull LocalDateTime startAt,
            @Schema(description = "차수 마감 시각(제외, KST). 이 시각부터 다음 차수가 적용된다") @NotNull LocalDateTime deadline) { }
}
