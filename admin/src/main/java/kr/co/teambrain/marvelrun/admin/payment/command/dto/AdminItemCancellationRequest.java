package kr.co.teambrain.marvelrun.admin.payment.command.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 관리자 상품 하나 취소(부분 취소) 요청. 금액은 서버가 그 구매 줄의 금액으로 정한다. */
public record AdminItemCancellationRequest(
        @Schema(description = "취소 사유(필수, 200자 이하)") @NotBlank @Size(max = 200) String reason
) {
}
