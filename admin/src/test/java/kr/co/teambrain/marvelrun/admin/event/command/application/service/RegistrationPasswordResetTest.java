package kr.co.teambrain.marvelrun.admin.event.command.application.service;

import kr.co.teambrain.marvelrun.admin.common.dto.request.PasswordResetRequest;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.repository.RegistrationCommandRepository;
import kr.co.teambrain.marvelrun.admin.user.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.admin.user.command.application.service.OrganizationCommandService;
import kr.co.teambrain.marvelrun.admin.user.command.repository.OrganizationCommandRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 관리자 초기화가 BCrypt 해시를 저장하고 기존 대상·길이 제한을 유지하는지 확인한다. */
class RegistrationPasswordResetTest {
    /** 외부 신청만 현장 출생일 기준을 적용하고 일반 신청의 기존 대회일 기준은 유지한다. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preservesPriceTierRulesForExternalAndNormalRegistrations(boolean external) {
        kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event event =
                mock(kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event.class);
        kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategory category =
                mock(kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategory.class);
        when(event.getId()).thenReturn("event");
        when(event.getStartDate()).thenReturn(java.time.LocalDateTime.of(2026, 11, 1, 9, 0));
        when(category.getName()).thenReturn("5km");
        Registration registration = Registration.builder().id("r").event(event).eventCategory(category)
                .externalPayment(external).birth("2013-11-01").build();
        when(registrations.findById("r")).thenReturn(Optional.of(registration));
        kr.co.teambrain.marvelrun.admin.event.command.application.dto.AdminRegistrationModifyRequest request =
                new kr.co.teambrain.marvelrun.admin.event.command.application.dto.AdminRegistrationModifyRequest(
                        "테스트", "01012345678", "2013-10-31", kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass.M,
                        "", "주소", "없음", "", "", "");
        if (external) {
            assertThatThrownBy(() -> personalService.modifyRegistrationBasicInfo("r", request))
                    .isInstanceOfSatisfying(CustomException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRICE_TIER_CHANGE_NOT_ALLOWED));
        } else {
            personalService.modifyRegistrationBasicInfo("r", request);
            assertThat(registration.getBirth()).isEqualTo("2013-10-31");
        }
    }
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final RegistrationCommandRepository registrations = mock(RegistrationCommandRepository.class);
    private final OrganizationCommandRepository organizations = mock(OrganizationCommandRepository.class);
    private final RegistrationCommandService personalService = new RegistrationCommandService(
            encoder, registrations, mock(AdminUnpaidRegistrationCancellationService.class));
    private final OrganizationCommandService organizationService = new OrganizationCommandService(
            encoder, organizations, registrations);

    /** 개인·단체 초기화 후 새 원문만 인증되며 요청 record의 원문은 유지된다. */
    @Test
    void resetsBothTargetsToHashes() {
        Registration registration = Registration.builder().password(encoder.encode("OldPassword1!")).build();
        Organization organization = organization(encoder.encode("OldPassword1!"));
        when(registrations.findById("r")).thenReturn(Optional.of(registration));
        when(organizations.findById("o")).thenReturn(Optional.of(organization));
        PasswordResetRequest request = new PasswordResetRequest("NewPassword1!");

        personalService.resetPersonalPassword("r", request);
        organizationService.resetOrganizationPassword("o", request);

        assertThat(encoder.matches(request.newPassword(), registration.getPassword())).isTrue();
        assertThat(encoder.matches(request.newPassword(), organization.getPassword())).isTrue();
        assertThat(encoder.matches("OldPassword1!", registration.getPassword())).isFalse();
        assertThat(encoder.matches("OldPassword1!", organization.getPassword())).isFalse();
        assertThat(registration.getPassword()).isNotEqualTo(request.newPassword());
        assertThat(organization.getPassword()).isNotEqualTo(request.newPassword());
        assertThat(request.newPassword()).isEqualTo("NewPassword1!");
        verify(registrations, never()).findAllByOrganization_Id(anyString());
    }

    /** 단체 구성원의 개별 비밀번호 초기화는 기존 규칙대로 차단한다. */
    @Test
    void rejectsIndividualResetOfOrganizationMember() {
        Registration registration = Registration.builder().organization(organization("unchanged"))
                .password("unchanged").build();
        when(registrations.findById("r")).thenReturn(Optional.of(registration));
        assertThatThrownBy(() -> personalService.resetPersonalPassword("r", new PasswordResetRequest("NewPassword1!")))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET));
        assertThat(registration.getPassword()).isEqualTo("unchanged");
    }

    /** 너무 짧거나 바이트 한도를 넘는 비밀번호는 기존 저장값을 변경하지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"short", "ascii", "korean"})
    void rejectsInvalidLengthWithoutChangingStoredValues(String kind) {
        String raw = switch (kind) {
            case "ascii" -> "a".repeat(73);
            case "korean" -> "가".repeat(25);
            default -> "12345";
        };
        ErrorCode expected = kind.equals("short") ? ErrorCode.INVALID_PASSWORD_LENGTH
                : ErrorCode.REGISTRATION_PASSWORD_TOO_LONG;
        Registration registration = Registration.builder().password("unchanged").build();
        Organization organization = organization("unchanged");
        when(registrations.findById("r")).thenReturn(Optional.of(registration));
        when(organizations.findById("o")).thenReturn(Optional.of(organization));
        PasswordResetRequest request = new PasswordResetRequest(raw);

        assertThatThrownBy(() -> personalService.resetPersonalPassword("r", request))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
        assertThatThrownBy(() -> organizationService.resetOrganizationPassword("o", request))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
        assertThat(registration.getPassword()).isEqualTo("unchanged");
        assertThat(organization.getPassword()).isEqualTo("unchanged");
    }
    /** 보호 생성자를 사용하는 단체 엔티티를 테스트용으로 생성한다. */
    private Organization organization(String password) {
        Organization organization = org.springframework.beans.BeanUtils.instantiateClass(Organization.class);
        organization.resetPasswordByAdmin(password);
        return organization;
    }
}
