package kr.co.teambrain.marvelrun.admin.event.query.dto;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentMethod;

import java.math.BigDecimal;

public record RegistrationStatDto(
        RegistrationStatus status,
        String organizationId,
        GenderClass gender,
        String birth,
        String courseName,
        BigDecimal contractAmount,
        PaymentMethod paymentMethod
) {
}