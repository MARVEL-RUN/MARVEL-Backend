package kr.co.teambrain.marvelrun.user.event.command.application.service;

import java.util.Optional;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationPasswordChangeRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.PersonalPasswordChangeRequest;
import kr.co.teambrain.marvelrun.user.event.command.repository.OrganizationCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** DB 없이 실제 BCrypt를 사용해 비밀번호 변경·실패 시 보존·바이트 제한을 검증한다. */
class RegistrationPasswordChangeServiceTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

    private final RegistrationCommandRepository registrations = mock(RegistrationCommandRepository.class);

    private final OrganizationCommandRepository organizations = mock(OrganizationCommandRepository.class);

    private final RegistrationPasswordChangeService service =
            new RegistrationPasswordChangeService(registrations, organizations, encoder);

    /** 개인·단체 모두 새 원문만 검증되고 계정 외 저장소에는 변경을 전파하지 않는다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changesPassword(boolean organization) {
        String oldHash = encoder.encode("OldPassword1!");
        Registration personal = Registration.builder().password(oldHash).build();
        Organization group = Organization.builder().password(oldHash).build();
        prepareTarget(organization, personal, group);

        change(organization, "OldPassword1!", "NewPassword2!");

        String savedHash = organization ? group.getPassword() : personal.getPassword();
        assertThat(encoder.matches("NewPassword2!", savedHash)).isTrue();
        assertThat(encoder.matches("OldPassword1!", savedHash)).isFalse();
        assertThat(savedHash).isNotEqualTo("NewPassword2!");

        if (organization) {
            verify(organizations).flush();
            verifyNoInteractions(registrations);
        } else {
            verify(registrations).flush();
            verifyNoInteractions(organizations);
        }
    }

    /** 잘못된 원문·해시 자체·72바이트 초과 입력은 저장값 변경 없이 거부한다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsWrongCurrentPassword(boolean organization) {
        String oldHash = encoder.encode("OldPassword1!");
        Registration personal = Registration.builder().password(oldHash).build();
        Organization group = Organization.builder().password(oldHash).build();
        prepareTarget(organization, personal, group);

        for (String wrong : new String[]{"wrong", oldHash, "a".repeat(73)}) {
            assertError(() -> change(organization, wrong, "NewPassword2!"),
                    organization ? ErrorCode.ORGANIZATION_ACCESS_DENIED : ErrorCode.REGISTRATION_ACCESS_DENIED);
        }

        assertThat(personal.getPassword()).isEqualTo(oldHash);
        assertThat(group.getPassword()).isEqualTo(oldHash);
        verify(registrations, never()).flush();
        verify(organizations, never()).flush();
    }

    /** 신규 원문 72바이트 초과는 실패하고, 정확히 72바이트 및 동일 원문 변경은 허용한다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesCreationByteLimitAndAllowsSamePassword(boolean organization) {
        String original = "가".repeat(24);
        String oldHash = encoder.encode(original);
        Registration personal = Registration.builder().password(oldHash).build();
        Organization group = Organization.builder().password(oldHash).build();
        prepareTarget(organization, personal, group);

        assertError(() -> change(organization, original, "가".repeat(25)),
                ErrorCode.REGISTRATION_PASSWORD_TOO_LONG);

        assertThat(organization ? group.getPassword() : personal.getPassword()).isEqualTo(oldHash);

        change(organization, original, original);

        String savedHash = organization ? group.getPassword() : personal.getPassword();
        assertThat(encoder.matches(original, savedHash)).isTrue();
        assertThat(savedHash).isNotEqualTo(oldHash);
    }

    /** 대상 누락·다른 대회·제외 대상은 저장소 조회 결과가 비어 있을 때 변경하지 않는다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsMissingTarget(boolean organization) {
        assertError(() -> change(organization, "OldPassword1!", "NewPassword2!"),
                organization ? ErrorCode.ORGANIZATION_NOT_FOUND : ErrorCode.REGISTRATION_NOT_FOUND);

        verify(registrations, never()).flush();
        verify(organizations, never()).flush();
    }

    /** 평문이나 손상된 저장 비밀번호에 대한 비교 우회를 허용하지 않는다. */
    @Test
    void rejectsInvalidStoredPasswords() {
        for (String stored : new String[]{null, "", "OldPassword1!", "$2a$invalid"}) {
            Registration personal = Registration.builder().password(stored).build();
            when(registrations.findPersonalPasswordChangeTarget("event", "target"))
                    .thenReturn(Optional.of(personal));

            assertError(() -> change(false, "OldPassword1!", "NewPassword2!"),
                    ErrorCode.REGISTRATION_ACCESS_DENIED);

            assertThat(personal.getPassword()).isEqualTo(stored);
        }
    }

    /** 단체 소속·삭제 개인 신청은 엔티티 변경 경계에서도 차단한다. */
    @Test
    void entityRejectsExcludedPersonalTargets() {
        Registration member = Registration.builder().organization(Organization.builder().build()).build();
        Registration deleted = Registration.builder().softDeleted(true).build();

        assertError(() -> member.changePassword("unused"), ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET);
        assertError(() -> deleted.changePassword("unused"), ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET);
    }

    /** 테스트 대상 종류에 해당하는 저장소 응답만 구성한다. */
    private void prepareTarget(boolean organization, Registration personal, Organization group) {
        if (organization) {
            when(organizations.findPasswordChangeTarget("event", "target")).thenReturn(Optional.of(group));
        } else {
            when(registrations.findPersonalPasswordChangeTarget("event", "target")).thenReturn(Optional.of(personal));
        }
    }

    /** 같은 시나리오를 두 종류의 변경 API 서비스에 적용한다. */
    private void change(boolean organization, String currentPassword, String newPassword) {
        if (organization) {
            service.changeOrganization("event", "target",
                    new OrganizationPasswordChangeRequest(currentPassword, newPassword));
        } else {
            service.changePersonal("event", "target",
                    new PersonalPasswordChangeRequest(currentPassword, newPassword));
        }
    }

    /** 실패가 기존 업무 오류 체계로 반환되는지 확인한다. */
    private void assertError(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CustomException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
