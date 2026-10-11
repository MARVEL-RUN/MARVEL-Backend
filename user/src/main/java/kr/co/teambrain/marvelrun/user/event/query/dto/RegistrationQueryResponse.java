package kr.co.teambrain.marvelrun.user.event.query.dto;

import java.math.BigDecimal;
import java.util.List;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyModels;

/* 개인 신청의 현재 값과 외부 결제 여부를 제공하며 비밀번호·내부 버전은 제외한다. */
public record RegistrationQueryResponse(
        String registrationId, String name, String email, String birth, String phNum, GenderClass gender,
        String eventCategoryId, String eventCategoryName,
        List<RegistrationSouvenirResponse> selectedSouvenirList,
        String address, String addressDetail,

        boolean guardianConsent,

        String guardianName,
        String guardianPhNum,
        String guardianRelationship,

        RegistrationStatus registrationStatus,
        BigDecimal totalAmount, BigDecimal paidAmount,
        PaymentProcessStatus paymentStatus, PaymentCancelStatus refundStatus,
        RegistrationPaymentAction paymentAction, String warningMessage,
        String paymentId, String orderId,
        boolean externalPayment,
        RegistrationActionPolicyModels.UserPolicy userPolicy
) { }
