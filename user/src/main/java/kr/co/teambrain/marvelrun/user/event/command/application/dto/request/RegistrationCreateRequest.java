package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.PersonalRegistrationPassword;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;

import java.util.List;

/**
 * 개인 신청의 그룹 선택·미선택 입력을 포함하며 비밀번호 정책은 변경 요청과 공유한다.
 *
 * 상품(단품·패키지) 구매는 items에 줄마다 상품·기념품·받는 사람을 담고 최상위 종목·기념품·entries는 비운다.
 * 일반 종목 신청은 items 없이 최상위 종목·기념품을 보낸다.
 */
public record RegistrationCreateRequest(

        /** 일반 종목 신청의 종목. items를 보내면 비운다. */
        String eventCategoryId,

        /** 일반 종목 신청의 기념품. items를 보내면 비운다. */
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
        String discountLinkCode,

        /** 쓰지 않는다. 받는 사람은 items[].entries로 보낸다. 값이 있으면 거절한다. */
        List<@NotNull @Valid RegistrationEntryRequest> entries,

        /** 상품 구매 줄. 한 신청에 10개까지 담는다. */
        List<@NotNull @Valid RegistrationItemRequest> items
) {
    /** 기존 내부 호출부의 일반 신청 입력을 호환한다. */
    public RegistrationCreateRequest(String eventCategoryId, List<@NotNull @Valid SouvenirJson> selectedSouvenirList, String password, String name, String phNum, String birth, GenderClass gender, String address, String addressDetail, Boolean guardianConsent, String guardianName, String guardianPhNum, String guardianRelationship, Boolean termsEssentialAgreed, Boolean termsMarketingAgreed, Boolean termsMarketingChannelAgreed, String email, String startGroupId, Boolean startGroupUnselected) {
        this(eventCategoryId, selectedSouvenirList, password, name, phNum, birth, gender, address, addressDetail, guardianConsent, guardianName, guardianPhNum, guardianRelationship, termsEssentialAgreed, termsMarketingAgreed, termsMarketingChannelAgreed, email, startGroupId, startGroupUnselected, null);
    }

    /** 참가 항목 입력이 없는 기존 호출부를 호환한다. */
    public RegistrationCreateRequest(String eventCategoryId, List<@NotNull @Valid SouvenirJson> selectedSouvenirList, String password, String name, String phNum, String birth, GenderClass gender, String address, String addressDetail, Boolean guardianConsent, String guardianName, String guardianPhNum, String guardianRelationship, Boolean termsEssentialAgreed, Boolean termsMarketingAgreed, Boolean termsMarketingChannelAgreed, String email, String startGroupId, Boolean startGroupUnselected, String discountLinkCode) {
        this(eventCategoryId, selectedSouvenirList, password, name, phNum, birth, gender, address, addressDetail, guardianConsent, guardianName, guardianPhNum, guardianRelationship, termsEssentialAgreed, termsMarketingAgreed, termsMarketingChannelAgreed, email, startGroupId, startGroupUnselected, discountLinkCode, null, null);
    }

    /** 구매 줄 입력이 없는 기존 호출부를 호환한다. */
    public RegistrationCreateRequest(String eventCategoryId, List<@NotNull @Valid SouvenirJson> selectedSouvenirList, String password, String name, String phNum, String birth, GenderClass gender, String address, String addressDetail, Boolean guardianConsent, String guardianName, String guardianPhNum, String guardianRelationship, Boolean termsEssentialAgreed, Boolean termsMarketingAgreed, Boolean termsMarketingChannelAgreed, String email, String startGroupId, Boolean startGroupUnselected, String discountLinkCode, List<@NotNull @Valid RegistrationEntryRequest> entries) {
        this(eventCategoryId, selectedSouvenirList, password, name, phNum, birth, gender, address, addressDetail, guardianConsent, guardianName, guardianPhNum, guardianRelationship, termsEssentialAgreed, termsMarketingAgreed, termsMarketingChannelAgreed, email, startGroupId, startGroupUnselected, discountLinkCode, entries, null);
    }

    /** 상품 구매 줄을 보냈는지 본다. */
    public boolean hasItems() {
        return items != null && !items.isEmpty();
    }

    /**
     * 일반 종목 신청은 최상위 종목·기념품이 있어야 하고,
     * 상품 구매(items)는 최상위 종목·기념품·entries를 비워야 한다.
     */
    @JsonIgnore
    @AssertTrue(message = "종목과 기념품을 선택하거나, 상품은 items로 보내주세요.")
    public boolean isCategorySelectionValid() {
        if (!hasItems()) {
            return eventCategoryId != null && !eventCategoryId.isBlank()
                    && selectedSouvenirList != null && !selectedSouvenirList.isEmpty();
        }
        return (eventCategoryId == null || eventCategoryId.isBlank())
                && (selectedSouvenirList == null || selectedSouvenirList.isEmpty())
                && (entries == null || entries.isEmpty());
    }

    /** 미선택이 아니면 선택 그룹의 식별자를 반드시 전달한다. */
    @JsonIgnore
    @AssertTrue(message = "그룹을 선택하거나 미선택을 체크해주세요.")
    public boolean isStartGroupSelectionValid() {
        return startGroupUnselected == null || startGroupUnselected
                || (startGroupId != null && !startGroupId.isBlank());
    }
}
