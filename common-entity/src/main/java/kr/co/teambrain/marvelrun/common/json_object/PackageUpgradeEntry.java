package kr.co.teambrain.marvelrun.common.json_object;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;

/**
 * 상향 승인 때 더할 참가 항목 한 개(패키지 하위 종목과 받는 사람).
 * 배송지는 신청 주소 하나를 쓰므로 두지 않는다.
 */
public record PackageUpgradeEntry(
        String eventCategoryId,
        String name,
        String phNum,
        String birth,
        GenderClass gender
) {
}
