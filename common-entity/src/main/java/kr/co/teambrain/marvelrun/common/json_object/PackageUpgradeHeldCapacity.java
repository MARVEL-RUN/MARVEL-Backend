package kr.co.teambrain.marvelrun.common.json_object;

/** 상향 요청 때 heldCount로 잡아 둔 정원 행과 수량. 승인·취소 때 이 수량만큼 heldCount를 돌려준다. */
public record PackageUpgradeHeldCapacity(
        String capacityId,
        int quantity
) {
}
