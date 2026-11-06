package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;

import java.util.List;

/**
 * 로그인한 주문자의 추가 주문. 상품 구매 줄과 이 주문의 배송지·보호자·약관 동의만 받는다.
 * 신청자 신원(이름·연락처·생년월일·성별)·이메일·비밀번호는 받지 않고 기존 주문에서 복사한다.
 * 필드 제한은 개인 신청(RegistrationCreateRequest)과 같다.
 */
public record ParticipantAdditionalOrderRequest(

        /** 상품 구매 줄. 한 주문에 10개까지 담는다. 일반 종목은 추가 주문할 수 없다. */
        @NotEmpty
        List<@NotNull @Valid RegistrationItemRequest> items,

        @Size(max = 300)
        String address,

        String addressDetail,

        Boolean guardianConsent,
        @Size(max = 50)
        String guardianName,
        @Size(max = 14)
        String guardianPhNum,
        @Size(max = 50)
        String guardianRelationship,

        @NotNull(message = "필수 약관 동의 여부가 누락되었습니다.")
        Boolean termsEssentialAgreed,

        @NotNull(message = "마케팅 활용 동의 여부가 누락되었습니다.")
        Boolean termsMarketingAgreed,

        @NotNull(message = "전자적 전송매체 수신 동의 여부가 누락되었습니다.")
        Boolean termsMarketingChannelAgreed,

        String startGroupId,
        @NotNull Boolean startGroupUnselected,
        @Size(max = 64)
        String discountLinkCode
) {

    /** 개인 신청과 같다. 미선택이 아니면 선택 그룹의 식별자를 반드시 전달한다. */
    @JsonIgnore
    @AssertTrue(message = "그룹을 선택하거나 미선택을 체크해주세요.")
    public boolean isStartGroupSelectionValid() {
        return startGroupUnselected == null || startGroupUnselected
                || (startGroupId != null && !startGroupId.isBlank());
    }

    /** 기존 주문에서 복사한 신원·이메일로 개인 신청 요청을 만든다. 비밀번호는 비워 두고 해시를 따로 넘긴다. */
    public RegistrationCreateRequest toCreateRequest(String name, String phNum, String birth, GenderClass gender,
                                                     String email) {
        return new RegistrationCreateRequest(
                null, null, null, name, phNum, birth, gender, address, addressDetail,
                guardianConsent, guardianName, guardianPhNum, guardianRelationship,
                termsEssentialAgreed, termsMarketingAgreed, termsMarketingChannelAgreed,
                email, startGroupId, startGroupUnselected, discountLinkCode, null, items);
    }
}
