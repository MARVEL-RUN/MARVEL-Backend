package kr.co.teambrain.marvelrun.admin.event.query.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDateTime;

/** 버추얼런 명단을 내려받을 차수의 마감 시각(KST)을 전달한다. */
public record VirtualRosterExcelRequest(
        @NotNull @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "차수 마감 시각(KST)", example = "2026-11-20T17:00:00") LocalDateTime deadline) {
}
