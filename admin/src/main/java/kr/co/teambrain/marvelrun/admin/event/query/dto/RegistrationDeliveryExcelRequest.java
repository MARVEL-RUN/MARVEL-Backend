package kr.co.teambrain.marvelrun.admin.event.query.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDateTime;

/** 배송 명단의 KST 조회 범위를 전달한다. 종료 시각은 포함하지 않는다. */
public record RegistrationDeliveryExcelRequest(
        @NotNull @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "KST 시작 시각(포함)", example = "2026-09-22T00:00:00") LocalDateTime startAt,
        @NotNull @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "KST 종료 시각(제외)", example = "2026-10-13T00:00:00") LocalDateTime endAt) {
}
