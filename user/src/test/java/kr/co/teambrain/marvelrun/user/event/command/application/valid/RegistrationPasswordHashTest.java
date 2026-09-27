package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.*;

/** 엔티티·프로젝션 인증이 실제 BCrypt 해시를 검증하고 평문 우회를 거부하는지 확인한다. */
class RegistrationPasswordHashTest {
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private static final String RAW = "TestPassword1!";

    /** 솔트가 서로 다른 저장 해시도 같은 원문으로 개인·단체 인증에 성공한다. */
    @Test
    void acceptsSaltedHashesForEntitiesAndProjections() {
        String first = encoder.encode(RAW);
        String second = encoder.encode(RAW);
        assertThat(first).isNotEqualTo(second);
        Registration registration = Registration.builder().name("참가자").birth("1990-01-01")
                .phNum("010-0000-0000").password(first).build();
        Organization organization = Organization.builder().loginId("group-login").password(second).build();

        assertThatCode(() -> RegistrationAccessVerifier.verifyPersonal(registration, personal(RAW), encoder))
                .doesNotThrowAnyException();
        assertThatCode(() -> RegistrationAccessVerifier.verifyPersonal("참가자", "1990-01-01",
                "010-0000-0000", second, personal(RAW), encoder)).doesNotThrowAnyException();
        assertThatCode(() -> RegistrationAccessVerifier.verifyOrganization(organization,
                new OrganizationAccessRequest("group-login", RAW), encoder)).doesNotThrowAnyException();
        assertThatCode(() -> RegistrationAccessVerifier.verifyOrganization("group-login", first,
                new OrganizationAccessRequest("group-login", RAW), encoder)).doesNotThrowAnyException();
    }

    /** 잘못된 원문과 해시 자체를 인증 입력으로 전달하는 시도를 거부한다. */
    @Test
    void rejectsWrongPasswordAndHashAsPassword() {
        String hash = encoder.encode(RAW);
        assertDenied("wrong-password", hash);
        assertDenied(hash, hash);
        assertDenied("가".repeat(25), hash);
        assertDenied(RAW, "$2a$99$" + ".".repeat(53));
    }

    /** UTF-8 72바이트 경계의 원문은 자르거나 정규화하지 않고 검증한다. */
    @Test
    void acceptsExactByteLimit() {
        String raw = "가".repeat(24);
        assertThatCode(() -> RegistrationAccessVerifier.verifyOrganization("group-login", encoder.encode(raw),
                new OrganizationAccessRequest("group-login", raw), encoder)).doesNotThrowAnyException();
    }

    /** 기존 평문과 잘못되거나 누락된 저장 해시는 원문 비교로 대체하지 않는다. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {RAW, "$2a$invalid", "   "})
    void rejectsNonHashStoredValues(String stored) {
        assertDenied(RAW, stored);
    }

    /** 원문이 맞아도 본인정보나 단체 로그인 ID가 다르면 접근을 거부한다. */
    @Test
    void retainsIdentityChecks() {
        String hash = encoder.encode(RAW);
        assertThatThrownBy(() -> RegistrationAccessVerifier.verifyPersonal("다른 이름", "1990-01-01",
                "010-0000-0000", hash, personal(RAW), encoder))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REGISTRATION_ACCESS_DENIED));
        assertThatThrownBy(() -> RegistrationAccessVerifier.verifyOrganization("other", hash,
                new OrganizationAccessRequest("group-login", RAW), encoder))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORGANIZATION_ACCESS_DENIED));
    }

    /** 요청 검증과 별개인 저장 엔티티 누락은 기존 접근 거부로 처리한다. */
    @Test
    void rejectsMissingStoredEntity() {
        assertThatThrownBy(() -> RegistrationAccessVerifier.verifyPersonal((Registration) null, personal(RAW), encoder))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REGISTRATION_ACCESS_DENIED));
        assertThatThrownBy(() -> RegistrationAccessVerifier.verifyOrganization((Organization) null,
                new OrganizationAccessRequest("group-login", RAW), encoder))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORGANIZATION_ACCESS_DENIED));
    }

    /** 개인·단체 인증 실패가 각각 기존 접근 거부 코드로 반환되는지 확인한다. */
    private void assertDenied(String raw, String stored) {
        assertThatThrownBy(() -> RegistrationAccessVerifier.verifyPersonal("참가자", "1990-01-01",
                "010-0000-0000", stored, personal(raw), encoder))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REGISTRATION_ACCESS_DENIED));
        assertThatThrownBy(() -> RegistrationAccessVerifier.verifyOrganization("group-login", stored,
                new OrganizationAccessRequest("group-login", raw), encoder))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORGANIZATION_ACCESS_DENIED));
    }

    /** 인증 요청에는 저장 해시가 아닌 사용자 입력 원문을 담는다. */
    private RegistrationAccessRequest personal(String password) {
        return new RegistrationAccessRequest("참가자", "1990-01-01", "010-0000-0000", password);
    }
}
