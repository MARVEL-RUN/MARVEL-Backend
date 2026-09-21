package kr.co.teambrain.marvelrun.user.common.exception.in_service;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** 활성 신청 인덱스 충돌만 기존 중복 오류로 식별하고 다른 제약은 오분류하지 않는지 검증한다. */
class RegistrationUniqueConstraintTest {
    /** 테이블명이 붙은 MySQL 인덱스 이름도 동일 제약으로 식별한다. */
    @Test
    void recognizesOnlyActiveRegistrationConstraint() {
        DataIntegrityViolationException exception = conflict("registration." + RegistrationUniqueConstraint.NAME);
        assertThat(RegistrationUniqueConstraint.matches(exception)).isTrue();
        assertThat(RegistrationUniqueConstraint.matches(conflict(RegistrationUniqueConstraint.NAME))).isTrue();
        assertThat(RegistrationUniqueConstraint.matches(conflict("uk_payment_order_id"))).isFalse();
        assertThat(RegistrationUniqueConstraint.matches(conflict(null))).isFalse();
        assertThat(RegistrationUniqueConstraint.matches(new RuntimeException(RegistrationUniqueConstraint.NAME))).isFalse();
        assertThat(RegistrationUniqueConstraint.matches(null)).isFalse();
    }

    /** 식별된 충돌의 HTTP 응답은 기존 중복 신청 오류를 따른다. */
    @Test
    void translatesToExistingBusinessError() {
        assertThat(new GlobalExceptionHandler().handleRegistrationUniqueConflict(conflict(RegistrationUniqueConstraint.NAME)).getBody())
                .usingRecursiveComparison()
                .isEqualTo(ErrorResponse.error(new CustomException(ErrorCode.REGISTRATION_ALREADY_EXISTS)).getBody());
    }

    /** 실제 개인정보 없이 Hibernate의 제약 이름 추출 결과를 구성한다. */
    private DataIntegrityViolationException conflict(String name) {
        return new DataIntegrityViolationException("테스트 충돌",
                new ConstraintViolationException("테스트 충돌", new SQLException("test", "23000", 1062), name));
    }
}
