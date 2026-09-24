package kr.co.teambrain.marvelrun.admin.event.query.dto;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record RegistrationStatDto(
        RegistrationStatus status,
        String organizationId,
        GenderClass gender,
        String birth,
        String courseName,
        BigDecimal contractAmount,
        BigDecimal paidAmount,
        PaymentMethod paymentMethod,
        LocalDateTime registrationDate
) {

    /** 기존 통계 조회의 생성자 호출을 유지한다. */
    public RegistrationStatDto(
            RegistrationStatus status,
            String organizationId,
            GenderClass gender,
            String birth,
            String courseName,
            BigDecimal contractAmount,
            BigDecimal paidAmount,
            PaymentMethod paymentMethod
    ) {
        this(
                status,
                organizationId,
                gender,
                birth,
                courseName,
                contractAmount,
                paidAmount,
                paymentMethod,
                null
        );
    }
}