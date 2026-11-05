package kr.co.teambrain.marvelrun.user.event.command.application.dto.response;

import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult.Order;

import java.math.BigDecimal;

/** 상향 결제창 정보. 결제 승인은 기존 공통 승인 API(POST /v1/public/payments/confirm)로 한다. */
public record PackageUpgradeOrderResponse(
        String upgradeId,
        String targetCategoryId,
        String targetCategoryName,
        String paymentId,
        String orderId,
        String orderName,
        BigDecimal amount
) {
    public static PackageUpgradeOrderResponse of(String upgradeId, String targetCategoryId,
            String targetCategoryName, Order order) {
        return new PackageUpgradeOrderResponse(upgradeId, targetCategoryId, targetCategoryName,
                order.paymentId(), order.orderId(), order.orderName(), order.amount());
    }
}
