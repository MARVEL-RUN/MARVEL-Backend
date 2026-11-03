package kr.co.teambrain.marvelrun.common.inheritance_enum;

// 신청 단위 키트 배송 상태. 배송이 없는 대회의 신청은 값을 두지 않는다.
public enum DeliveryStatus {
    PREPARING, // 준비 중
    SHIPPING, // 배송 중
    DELIVERED, // 배송 완료
}
