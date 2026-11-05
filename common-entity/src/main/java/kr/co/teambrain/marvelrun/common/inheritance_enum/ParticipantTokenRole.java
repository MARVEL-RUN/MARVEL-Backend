package kr.co.teambrain.marvelrun.common.inheritance_enum;

import java.util.Arrays;

// 참가자에게 발급하는 JWT의 role 클레임. 두 앱이 서명 키를 같이 쓰므로 관리자 앱은 이 역할의 토큰을 거부한다.
// 관리자 역할은 DB(role.name)에 문자열로 있으므로 여기에 넣지 않는다.
public enum ParticipantTokenRole {
    PARTICIPANT; // 개인 본인확인(이름·생년월일·휴대폰·비밀번호)으로 로그인한 참가자. subject는 신청 id

    // 토큰의 role 값이 참가자 역할이면 true를 반환한다.
    public static boolean includes(String roleName) {
        return roleName != null && Arrays.stream(values()).anyMatch(role -> role.name().equals(roleName));
    }
}
