package kr.co.teambrain.marvelrun.common.inheritance_enum.upgrade;

// 단품 → 패키지 상향 대기의 상태. 신청마다 PENDING은 하나만 있다(uk_package_upgrade_active).
public enum PackageUpgradeStatus {
    PENDING,   // 정원을 잡아 두고 상향 결제를 기다린다
    COMPLETED, // 상향 결제가 승인되어 패키지로 바뀌었다
    CANCELED   // 참가자·관리자 취소나 신청 취소로 잡아 둔 정원을 돌려줬다
}
