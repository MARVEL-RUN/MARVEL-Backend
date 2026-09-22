package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationModificationParticipantRequest;
import kr.co.teambrain.marvelrun.user.event.command.repository.OrganizationCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrgRegistrationModificationAccessValidatorTest {

    private static final String EVENT_ID =
            "event-1";

    private static final String ORGANIZATION_ID =
            "organization-1";

    private static final LocalDateTime NOW =
            LocalDateTime.of(
                    2026,
                    9,
                    19,
                    22,
                    0
            );

    @Mock
    private OrganizationCommandRepository
            organizationCommandRepository;

    @Mock
    private RegistrationCommandRepository
            registrationCommandRepository;

    @Mock
    private Organization
            organization;

    @Mock
    private Event
            event;

    @Mock
    private Registration
            registrationA;

    @Mock
    private Registration
            registrationB;

    @InjectMocks
    private OrgRegistrationModificationAccessValidator
            validator;


    /**
     * 단체 로그인정보가 일치하고 요청의 기존 Registration ID가
     * 모두 현재 Organization 소속이면 Context를 생성한다.
     */
    @Test
    void validatesOrganizationAccessAndCreatesContext() {

        stubOrganization();

        when(organization.getEvent())
                .thenReturn(event);

        when(registrationA.getId())
                .thenReturn("registration-a");

        when(registrationB.getId())
                .thenReturn("registration-b");

        when(
                registrationCommandRepository
                        .findAllActiveByEventAndOrganization(
                                EVENT_ID,
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                List.of(
                        registrationA,
                        registrationB
                )
        );

        OrgRegistrationModificationRequest request =
                request(
                        access(
                                "group-login",
                                "password"
                        ),
                        List.of(
                                participant(
                                        "registration-a"
                                ),
                                participant(
                                        "registration-b"
                                )
                        )
                );

        OrgRegistrationModificationAccessContext context =
                validator.validate(
                        EVENT_ID,
                        ORGANIZATION_ID,
                        request,
                        NOW
                );

        assertThat(context.event())
                .isSameAs(event);

        assertThat(context.organization())
                .isSameAs(organization);

        assertThat(context.currentRegistrations())
                .containsExactly(
                        registrationA,
                        registrationB
                );

        assertThat(context.request())
                .isSameAs(request);

        assertThat(context.now())
                .isEqualTo(NOW);
    }


    /**
     * 이전 조회에서 인증했더라도 실제 수정 요청의
     * 단체 비밀번호가 다르면 다시 차단한다.
     */
    @Test
    void rejectsWrongOrganizationPassword() {

        stubOrganization();

        OrgRegistrationModificationRequest request =
                request(
                        access(
                                "group-login",
                                "wrong-password"
                        ),
                        List.of(
                                participant(
                                        null
                                )
                        )
                );

        assertThatThrownBy(
                () ->
                        validator.validate(
                                EVENT_ID,
                                ORGANIZATION_ID,
                                request,
                                NOW
                        )
        )
                .isInstanceOf(CustomException.class)
                .satisfies(
                        exception ->
                                assertThat(
                                        ((CustomException) exception)
                                                .getErrorCode()
                                ).isEqualTo(
                                        ErrorCode.ORGANIZATION_ACCESS_DENIED
                                )
                );
    }


    /**
     * 같은 기존 Registration ID가 최종목록에 두 번 들어오면
     * 동일 구성원을 중복 수정하는 요청으로 차단한다.
     */
    @Test
    void rejectsDuplicateExistingRegistrationId() {

        stubOrganization();

        when(registrationA.getId())
                .thenReturn("registration-a");

        when(
                registrationCommandRepository
                        .findAllActiveByEventAndOrganization(
                                EVENT_ID,
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                List.of(
                        registrationA
                )
        );

        OrgRegistrationModificationRequest request =
                request(
                        access(
                                "group-login",
                                "password"
                        ),
                        List.of(
                                participant(
                                        "registration-a"
                                ),
                                participant(
                                        "registration-a"
                                )
                        )
                );

        assertThatThrownBy(
                () ->
                        validator.validate(
                                EVENT_ID,
                                ORGANIZATION_ID,
                                request,
                                NOW
                        )
        )
                .isInstanceOf(CustomException.class)
                .satisfies(
                        exception ->
                                assertThat(
                                        ((CustomException) exception)
                                                .getErrorCode()
                                ).isEqualTo(
                                        ErrorCode.DUPLICATE_REGISTRATION_MODIFICATION_TARGET
                                )
                );
    }


    /**
     * 현재 Organization에 속하지 않은 Registration ID를
     * 수정 대상으로 넘기면 차단한다.
     */
    @Test
    void rejectsRegistrationFromAnotherOrganization() {

        stubOrganization();

        when(registrationA.getId())
                .thenReturn("registration-a");

        when(
                registrationCommandRepository
                        .findAllActiveByEventAndOrganization(
                                EVENT_ID,
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                List.of(
                        registrationA
                )
        );

        OrgRegistrationModificationRequest request =
                request(
                        access(
                                "group-login",
                                "password"
                        ),
                        List.of(
                                participant(
                                        "registration-other"
                                )
                        )
                );

        assertThatThrownBy(
                () ->
                        validator.validate(
                                EVENT_ID,
                                ORGANIZATION_ID,
                                request,
                                NOW
                        )
        )
                .isInstanceOf(CustomException.class)
                .satisfies(
                        exception ->
                                assertThat(
                                        ((CustomException) exception)
                                                .getErrorCode()
                                ).isEqualTo(
                                        ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET
                                )
                );
    }


    /**
     * registrationId가 null인 참가자는
     * 신규 구성원 후보이므로 정상 허용한다.
     */
    @Test
    void allowsParticipantWithNullRegistrationIdAsNewMember() {

        stubOrganization();

        when(organization.getEvent())
                .thenReturn(event);

        when(
                registrationCommandRepository
                        .findAllActiveByEventAndOrganization(
                                EVENT_ID,
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                List.of()
        );

        OrgRegistrationModificationRequest request =
                request(
                        access(
                                "group-login",
                                "password"
                        ),
                        List.of(
                                participant(
                                        null
                                )
                        )
                );

        OrgRegistrationModificationAccessContext context =
                validator.validate(
                        EVENT_ID,
                        ORGANIZATION_ID,
                        request,
                        NOW
                );

        assertThat(context.request())
                .isSameAs(request);
    }


    /**
     * 기존 구성원을 최종목록에서 생략하는 것은
     * Step 13에서 처리할 제거 후보이므로 Access 단계에서는 허용한다.
     */
    @Test
    void allowsExistingMemberToBeOmittedAsRemovalCandidate() {

        stubOrganization();

        when(organization.getEvent())
                .thenReturn(event);

        when(registrationA.getId())
                .thenReturn("registration-a");

        when(registrationB.getId())
                .thenReturn("registration-b");

        when(
                registrationCommandRepository
                        .findAllActiveByEventAndOrganization(
                                EVENT_ID,
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                List.of(
                        registrationA,
                        registrationB
                )
        );

        /*
         * registration-b만 최종목록에 전달.
         * registration-a는 제거 후보가 된다.
         */
        OrgRegistrationModificationRequest request =
                request(
                        access(
                                "group-login",
                                "password"
                        ),
                        List.of(
                                participant(
                                        "registration-b"
                                )
                        )
                );

        OrgRegistrationModificationAccessContext context =
                validator.validate(
                        EVENT_ID,
                        ORGANIZATION_ID,
                        request,
                        NOW
                );

        assertThat(context.currentRegistrations())
                .containsExactly(
                        registrationA,
                        registrationB
                );

        assertThat(
                context.request()
                        .registrations()
        ).hasSize(1);
    }


    /**
     * 테스트용 현재 Organization 인증값을 설정한다.
     */
    private void stubOrganization() {

        when(
                organizationCommandRepository
                        .findModificationTarget(
                                EVENT_ID,
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                Optional.of(
                        organization
                )
        );

        when(organization.getLoginId())
                .thenReturn("group-login");

        when(organization.getPassword())
                .thenReturn("password");
    }


    /**
     * 단체 수정 요청용 인증 입력을 생성한다.
     */
    private OrganizationAccessRequest access(
            String loginId,
            String password
    ) {

        return new OrganizationAccessRequest(
                loginId,
                password
        );
    }


    /**
     * 테스트용 단체 구성원 수정 후보를 생성한다.
     */
    private OrgRegistrationModificationParticipantRequest participant(
            String registrationId
    ) {

        return new OrgRegistrationModificationParticipantRequest(
                registrationId,
                "category-1",
                List.of(),
                "참가자",
                "010-1111-2222",
                "1990-01-01",
                GenderClass.M
        );
    }


    /**
     * 단체 수정 요청을 생성한다.
     */
    private OrgRegistrationModificationRequest request(
            OrganizationAccessRequest access,
            List<OrgRegistrationModificationParticipantRequest> registrations
    ) {

        return new OrgRegistrationModificationRequest(false,
                "test@example.com",
                "테스트 주소",
                "상세",
                "테스트 단체장",
                java.time.LocalDate.of(1990, 1, 1),
                "010-0000-0000",
                access,
                registrations);
    }
}