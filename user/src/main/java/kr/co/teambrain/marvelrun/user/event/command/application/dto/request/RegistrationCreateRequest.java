package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.PersonalRegistrationPassword;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;

import java.util.List;

/** 개인 신청의 그룹 선택·미선택 입력을 포함하며 비밀번호 정책은 변경 요청과 공유한다. */
public record RegistrationCreateRequest(

        @NotBlank
        String eventCategoryId,

        @NotEmpty
        List<@NotNull @Valid SouvenirJson> selectedSouvenirList,

        @PersonalRegistrationPassword
        String password,

        @NotBlank
        @Size(max = 50)
        String name,

        @NotBlank
        @Size(max = 14)
        String phNum,

        @NotBlank
        @Size(max = 10)
        String birth,

        @NotNull
        GenderClass gender,

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

        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,
        String startGroupId,
        @NotNull Boolean startGroupUnselected,
        @Size(max = 64)
        String discountLinkCode
) {
    /** 기존 내부 호출부의 일반 신청 입력을 호환한다. */
    public RegistrationCreateRequest(String eventCategoryId, List<@NotNull @Valid SouvenirJson> selectedSouvenirList, String password, String name, String phNum, String birth, GenderClass gender, String address, String addressDetail, Boolean guardianConsent, String guardianName, String guardianPhNum, String guardianRelationship, Boolean termsEssentialAgreed, Boolean termsMarketingAgreed, Boolean termsMarketingChannelAgreed, String email, String startGroupId, Boolean startGroupUnselected) {
        this(eventCategoryId, selectedSouvenirList, password, name, phNum, birth, gender, address, addressDetail, guardianConsent, guardianName, guardianPhNum, guardianRelationship, termsEssentialAgreed, termsMarketingAgreed, termsMarketingChannelAgreed, email, startGroupId, startGroupUnselected, null);
    }

    /** 미선택이 아니면 선택 그룹의 식별자를 반드시 전달한다. */
    @JsonIgnore
    @AssertTrue(message = "그룹을 선택하거나 미선택을 체크해주세요.")
    public boolean isStartGroupSelectionValid() {
        return startGroupUnselected == null || startGroupUnselected
                || (startGroupId != null && !startGroupId.isBlank());
    }
}
