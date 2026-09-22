package kr.co.teambrain.marvelrun.admin.payment.command.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** 참가 취소를 동반하는 결제액 환불의 대상과 요청 식별자를 받는다. 금액은 서버에서 계산한다. */
public record AdminPaymentRefundRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 200) String reason,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 40) String> registrationIds,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 40) String> organizationIds
) {
    /** 직접 신청 또는 단체 전체 중 적어도 하나를 선택하고 요청 크기를 제한한다. */
    @JsonIgnore
    @AssertTrue(message = "환불 대상은 합계 1~100개 선택해야 합니다.")
    public boolean isTargetSelectionValid() {
        if (registrationIds == null || organizationIds == null) { return true; }
        int count = registrationIds.size() + organizationIds.size();
        return count > 0 && count <= 100;
    }
}
