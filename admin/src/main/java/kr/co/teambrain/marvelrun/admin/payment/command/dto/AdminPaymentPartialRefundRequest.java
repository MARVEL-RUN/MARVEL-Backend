package kr.co.teambrain.marvelrun.admin.payment.command.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** 정책으로 재계산할 참가자별 변경 내용을 받으며 관리자 지정 환불액은 받지 않는다. */
public record AdminPaymentPartialRefundRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 200) String reason,
        @NotEmpty @Size(max = 100) List<@NotNull @Valid AdminPaymentPartialRefundTarget> targets
) {
    /** 같은 참가자의 서로 다른 변경 지시가 한 요청에서 충돌하지 않도록 중복을 거절한다. */
    @JsonIgnore
    @AssertTrue(message = "부분환불 대상 신청 ID는 중복될 수 없습니다.")
    public boolean isUniqueRegistrationSelection() {
        if (targets == null) { return true; }
        List<String> ids = targets.stream().filter(java.util.Objects::nonNull)
                .map(AdminPaymentPartialRefundTarget::registrationId)
                .filter(java.util.Objects::nonNull).toList();
        return ids.stream().distinct().count() == ids.size();
    }
}
