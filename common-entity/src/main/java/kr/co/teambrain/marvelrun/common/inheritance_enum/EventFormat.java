package kr.co.teambrain.marvelrun.common.inheritance_enum;

// 배송지 필수·송장·인증 제출처럼 버추얼런에만 있는 기능을 가른다.
// 참가자 인증 방식, 배번 발급, 단체 신청 허용은 각자의 설정값이 정한다.
public enum EventFormat {
    OFFLINE, // 현장 대회
    VIRTUAL, // 버추얼런
}
