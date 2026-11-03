package kr.co.teambrain.marvelrun.common.inheritance_enum;

// 참가자가 자기 신청에 들어오는 방식. event.participant_auth_method에 이름 그대로 저장한다.
public enum ParticipantAuthMethod {
    PERSONAL_INFO_PASSWORD, // 이름·생년월일·휴대폰 + 비밀번호(기존 대회)
    BIB_PHONE_OTP, // 배번 + 휴대폰 인증번호
}
