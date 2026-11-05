package kr.co.teambrain.marvelrun.admin.event.command.application.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 남은 상향 대기 일괄 취소 요청. deadline은 차수 마감 시각(켜져 있는 PAYMENT 정책의 effective_from)이다.
 * 그 시각 전에 만든 신청의 상향 대기를 대상으로 한다.
 */
public record PackageUpgradeBatchCancellationRequest(
        @NotNull LocalDateTime deadline
) {
}
