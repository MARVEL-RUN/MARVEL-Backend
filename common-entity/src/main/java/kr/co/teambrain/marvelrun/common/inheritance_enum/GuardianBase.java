package kr.co.teambrain.marvelrun.common.inheritance_enum;

// 소유 신청에서만 활용하는 enum
// 소유신청의 대상 보호자를 단체장으로 설정하거나 기타 내역(직접 기입)으로 처리 가능
// 즉, ORG_LEADER인 케이스라면 guardianPhNum, guardianRelationShip이 별도의 값을 소유해서는 안된다.
public enum GuardianBase {
    ORG_LEADER, // 단체장 위임
    OTHER // 기타
}
