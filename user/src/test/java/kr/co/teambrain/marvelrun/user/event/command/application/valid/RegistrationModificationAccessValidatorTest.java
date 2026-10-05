package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** 실제 BCrypt 인코더로 수정 요청의 원문과 저장 해시를 검증한다. */
@ExtendWith(MockitoExtension.class)
class RegistrationModificationAccessValidatorTest {

    private static final String EVENT_ID =
            "event-1";

    private static final String REGISTRATION_ID =
            "registration-1";

    private static final LocalDateTime NOW =
            LocalDateTime.of(
                    2026,
                    9,
                    19,
                    22,
                    0
            );

    @Mock
    private RegistrationCommandRepository
            registrationCommandRepository;

    @Mock
    private Registration
            registration;

    @Mock
    private Event
            event;

    @org.mockito.Spy
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder =
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);

    @InjectMocks
    private RegistrationModificationAccessValidator
            validator;


    /* 인증된 외부 결제 신청도 변경 Context를 만들지 않으며 잘못된 인증은 기존 오류를 유지한다. */
    @Test
    void rejectsExternalPaymentAfterIdentityVerification() {
        // 실제 엔티티와 해시로 인증·외부 결제 검증을 함께 확인한다.
        Registration external = Registration.builder().id(REGISTRATION_ID).externalPayment(true)
                .name("외부결제자").birth("1990-01-01").phNum("010-1111-2222")
                .password(passwordEncoder.encode("password")).build();
        when(registrationCommandRepository.findActivePersonalModificationTarget(EVENT_ID, REGISTRATION_ID))
                .thenReturn(Optional.of(external));

        // 유효한 인증만 외부 결제 전용 오류로 거절한다.
        assertThatThrownBy(() -> validator.validate(EVENT_ID, REGISTRATION_ID,
                request(access("외부결제자", "1990-01-01", "010-1111-2222", "password")), NOW))
                .isInstanceOfSatisfying(CustomException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_PAYMENT_REGISTRATION_RESTRICTED));
        assertThatThrownBy(() -> validator.validate(EVENT_ID, REGISTRATION_ID,
                request(access("외부결제자", "1990-01-01", "010-1111-2222", "wrong")), NOW))
                .isInstanceOfSatisfying(CustomException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.REGISTRATION_ACCESS_DENIED));
    }

    /* 제한 오류는 기존 응답 변환기를 통해 HTTP 409와 전용 코드를 반환한다. */
    @Test
    void exposesExternalPaymentRestrictionAsConflict() {
        // 기존 예외 응답 체계가 새 업무 코드를 다른 오류로 마스킹하지 않는지 확인한다.
        ResponseEntity<ErrorResponse> response =
                ErrorResponse.error(
                        new CustomException(ErrorCode.EXTERNAL_PAYMENT_REGISTRATION_RESTRICTED));

        // 프론트가 외부 신청 제한을 구별할 수 있는 계약을 확인한다.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("EXTERNAL_PAYMENT_REGISTRATION_RESTRICTED");
    }

    /**
     * 수정 요청에 다시 전달된 개인 본인확인 정보가
     * 현재 Registration 값과 모두 일치하면 Context를 생성한다.
     */
    @Test
    void validatesModificationAccessAndCreatesContext() {

        RegistrationModificationRequest request =
                request(
                        access(
                                "홍길동",
                                "1990-01-01",
                                "010-1111-2222",
                                "password"
                        )
                );

        when(
                registrationCommandRepository
                        .findActivePersonalModificationTarget(
                                EVENT_ID,
                                REGISTRATION_ID
                        )
        ).thenReturn(
                Optional.of(
                        registration
                )
        );

        when(registration.getName())
                .thenReturn("홍길동");

        when(registration.getBirth())
                .thenReturn("1990-01-01");

        when(registration.getPhNum())
                .thenReturn("010-1111-2222");

        // Spy 호출은 다른 mock의 stubbing을 시작하기 전에 완료한다.
        String encodedPassword = passwordEncoder.encode("password");
        when(registration.getPassword())
                .thenReturn(encodedPassword);

        when(registration.getEvent())
                .thenReturn(event);

        RegistrationModificationAccessContext context =
                validator.validate(
                        EVENT_ID,
                        REGISTRATION_ID,
                        request,
                        NOW
                );

        assertThat(context.event())
                .isSameAs(event);

        assertThat(context.registration())
                .isSameAs(registration);

        assertThat(context.request())
                .isSameAs(request);

        assertThat(context.now())
                .isEqualTo(NOW);
    }


    /**
     * 조회 과정에서 이전에 인증했는지와 무관하게,
     * 실제 수정 요청의 비밀번호가 현재 Registration과 다르면 차단한다.
     */
    @Test
    void rejectsWrongPasswordOnModificationRequest() {

        RegistrationModificationRequest request =
                request(
                        access(
                                "홍길동",
                                "1990-01-01",
                                "010-1111-2222",
                                "wrong-password"
                        )
                );

        stubExistingRegistration();

        assertThatThrownBy(
                () ->
                        validator.validate(
                                EVENT_ID,
                                REGISTRATION_ID,
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
                                        ErrorCode.REGISTRATION_ACCESS_DENIED
                                )
                );
    }


    /**
     * 비밀번호가 맞더라도 이름·생년월일·전화번호 중
     * 하나라도 현재 Registration과 다르면 수정 권한을 주지 않는다.
     */
    @Test
    void rejectsMismatchedPersonalIdentityInformation() {

        RegistrationModificationRequest request =
                request(
                        access(
                                "다른이름",
                                "1990-01-01",
                                "010-1111-2222",
                                "password"
                        )
                );

        when(
                registrationCommandRepository
                        .findActivePersonalModificationTarget(
                                EVENT_ID,
                                REGISTRATION_ID
                        )
        ).thenReturn(
                Optional.of(
                        registration
                )
        );

        when(registration.getName())
                .thenReturn("홍길동");

        assertThatThrownBy(
                () ->
                        validator.validate(
                                EVENT_ID,
                                REGISTRATION_ID,
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
                                        ErrorCode.REGISTRATION_ACCESS_DENIED
                                )
                );

        assertThatThrownBy(
                () ->
                        validator.validate(
                                EVENT_ID,
                                REGISTRATION_ID,
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
                                        ErrorCode.REGISTRATION_ACCESS_DENIED
                                )
                );
    }


    /**
     * 다른 Event, 단체 신청, 삭제 신청 등 Repository의
     * 수정 대상 조건에 맞지 않는 Registration은 존재하지 않는 대상으로 처리한다.
     */
    @Test
    void rejectsRegistrationThatIsNotActivePersonalModificationTarget() {

        when(
                registrationCommandRepository
                        .findActivePersonalModificationTarget(
                                EVENT_ID,
                                REGISTRATION_ID
                        )
        ).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(
                () ->
                        validator.validate(
                                EVENT_ID,
                                REGISTRATION_ID,
                                request(
                                        access(
                                                "홍길동",
                                                "1990-01-01",
                                                "010-1111-2222",
                                                "password"
                                        )
                                ),
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
                                        ErrorCode.REGISTRATION_NOT_FOUND
                                )
                );
    }


    /**
     * 현재 존재하는 개인 Registration의 인증값을 공통으로 설정한다.
     */
    private void stubExistingRegistration() {

        when(
                registrationCommandRepository
                        .findActivePersonalModificationTarget(
                                EVENT_ID,
                                REGISTRATION_ID
                        )
        ).thenReturn(
                Optional.of(
                        registration
                )
        );

        when(registration.getName())
                .thenReturn("홍길동");

        when(registration.getBirth())
                .thenReturn("1990-01-01");

        when(registration.getPhNum())
                .thenReturn("010-1111-2222");

        // Spy 호출은 다른 mock의 stubbing을 시작하기 전에 완료한다.
        String encodedPassword = passwordEncoder.encode("password");
        when(registration.getPassword())
                .thenReturn(encodedPassword);
    }


    /**
     * 개인 수정 요청에 포함되는 본인확인 입력을 생성한다.
     */
    private RegistrationAccessRequest access(
            String name,
            String birth,
            String phNum,
            String password
    ) {

        return new RegistrationAccessRequest(
                name,
                birth,
                phNum,
                password
        );
    }


    /**
     * Access Validator 테스트에 필요한 최소 개인 수정 요청을 생성한다.
     *
     * 후보값 정책검증은 Step 8의 책임이므로
     * 이 테스트에서는 access 외 필드를 검증하지 않는다.
     */
    private RegistrationModificationRequest request(
            RegistrationAccessRequest access
    ) {

        return new RegistrationModificationRequest(access,
                "category-1",
                List.of(),
                "수정후이름",
                "010-9999-9999",
                "1990-01-01",
                GenderClass.M,
                "수정 주소",
                "상세",
                false,
                null,
                null,
                null,
                null);
    }
}
