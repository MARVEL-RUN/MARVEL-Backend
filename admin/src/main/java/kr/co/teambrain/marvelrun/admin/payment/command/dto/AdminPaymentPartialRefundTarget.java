package kr.co.teambrain.marvelrun.admin.payment.command.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;

/** 종목·기념품 변경 후보와 0원 계약 유지 설정이다. 가격 입력과 일반 개인정보 수정은 포함하지 않는다. birth 생략은 기존 값 유지다. */
public record AdminPaymentPartialRefundTarget(
        @NotBlank @Size(max = 40) String registrationId,
        @NotBlank @Size(max = 40) String eventCategoryId,
        @NotEmpty @Size(max = 100) List<@NotNull @Valid SouvenirJson> selectedSouvenirList,
        @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}") String birth,
        Boolean keepParticipationWhenZero
) {
    /** 기존 내부 호출의 생년월일 유지 계약을 보존한다. */
    public AdminPaymentPartialRefundTarget(String registrationId, String eventCategoryId,
            List<SouvenirJson> selectedSouvenirList, Boolean keepParticipationWhenZero) {
        this(registrationId, eventCategoryId, selectedSouvenirList, null, keepParticipationWhenZero);
    }
    /** 0원 계약에 대한 설정을 생략하면 참가·정원 유지 의사를 기본으로 적용한다. */
    public AdminPaymentPartialRefundTarget {
        keepParticipationWhenZero = keepParticipationWhenZero == null ? Boolean.TRUE : keepParticipationWhenZero;
    }
}
