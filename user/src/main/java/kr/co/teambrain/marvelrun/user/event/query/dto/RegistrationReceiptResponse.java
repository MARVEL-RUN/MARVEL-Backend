package kr.co.teambrain.marvelrun.user.event.query.dto;

import java.math.BigDecimal;
import java.util.List;

/** 접수 확인 화면에 필요한 참가자·금액·결제 동작만 제공한다. */
public record RegistrationReceiptResponse(
        String registrationId,
        String organizationId,
        String organizationName,
        List<Member> members,
        List<SouvenirItem> souvenirs,
        BigDecimal totalAmount,
        BigDecimal paidAmount,
        DisplayStatus paymentStatus,
        String paymentStatusLabel,
        String warningMessage,
        PaymentAction paymentAction,
        String paymentId,
        String orderId
) {
    /** 현재 선택과 취소 여부를 표시하며 개인정보 전체나 금융 원장은 포함하지 않는다. */
    public record Member(
            String registrationId, String name, String eventCategoryName,
            List<SouvenirItem> souvenirs, boolean canceled, String registrationStatus
    ) { }

    /** 같은 기념품 ID·사이즈 기준의 수량이며 이름이 같은 서로 다른 상품은 합치지 않는다. */
    public record SouvenirItem(String souvenirId, String name, String size, int quantity) { }

    /** 금액·처리 상태를 조합한 사용자 화면용 상태이며 DB enum을 변경하지 않는다. */
    public enum DisplayStatus {
        PAYMENT_PENDING, ADDITIONAL_PAYMENT_REQUIRED, PAYMENT_PROCESSING,
        PAYMENT_UNKNOWN, CONFIRMED, REFUND_REQUIRED, REFUND_PROCESSING,
        REFUND_UNKNOWN, REFUND_FAILED, CANCELLATION_PENDING, CANCELED,
        DATA_CHECK_REQUIRED
    }

    /** 버튼 실행은 재준비 요청이며 실제 결제 허가는 그 요청에서 다시 검증한다. */
    public enum PaymentAction {
        PREPARE_PAYMENT, WAIT, NONE, PAYMENT_CLOSED, CONTACT_SUPPORT
    }
}
