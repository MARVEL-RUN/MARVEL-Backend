package kr.co.teambrain.marvelrun.common.inheritance_enum;

// 참가자가 자기 신청에 들어오는 방식. event.participant_auth_method에 이름 그대로 저장한다.
// 코드는 방식 이름이 아니라 식별·확인 종류로 가른다.
public enum ParticipantAuthMethod {
    PERSONAL_INFO_PASSWORD(ParticipantIdentifierType.PERSONAL_INFO, ParticipantCredentialType.PASSWORD), // 이름·생년월일·휴대폰 + 비밀번호(기존 대회)
    BIB_PHONE_OTP(ParticipantIdentifierType.BIB, ParticipantCredentialType.PHONE_OTP); // 배번 + 휴대폰 인증번호

    private final ParticipantIdentifierType identifierType;
    private final ParticipantCredentialType credentialType;

    ParticipantAuthMethod(ParticipantIdentifierType identifierType, ParticipantCredentialType credentialType) {
        this.identifierType = identifierType;
        this.credentialType = credentialType;
    }

    public ParticipantIdentifierType getIdentifierType() {
        return identifierType;
    }

    public ParticipantCredentialType getCredentialType() {
        return credentialType;
    }

    // 이름·생년월일·휴대폰으로 찾고 비밀번호로 확인하는 기존 신청 API를 쓸 수 있는 방식인지 반환한다.
    public boolean usesPersonalInfoPassword() {
        return identifierType == ParticipantIdentifierType.PERSONAL_INFO
                && credentialType == ParticipantCredentialType.PASSWORD;
    }
}
