package kr.co.teambrain.marvelrun.user.event.query.dto;

import java.math.BigDecimal;
import java.util.List;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;

/** 단체 정보와 현재 구성원 목록, 단체 단위 결제 안내를 한 단계로 제공한다. */
public record OrgRegistrationQueryResponse(
        String organizationId, String organizationName, String loginId,
        String leaderName, String leaderBirth, String leaderPhNum, String email,
        String address, String addressDetail,
        List<OrgRegistrationParticipantResponse> registrations,
        BigDecimal totalAmount, BigDecimal paidAmount,
        RegistrationStatus registrationStatus,
        PaymentProcessStatus paymentStatus, PaymentCancelStatus refundStatus,
        RegistrationPaymentAction paymentAction, String warningMessage,
        String paymentId, String orderId
) { }
