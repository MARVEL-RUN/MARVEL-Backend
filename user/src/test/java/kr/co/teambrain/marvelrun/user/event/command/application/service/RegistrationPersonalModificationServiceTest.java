package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityRequirementDiff;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.CapacityModificationService;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.CapacityRequirementResolver;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationCapacityService;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationCapacityDiffService;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationModificationCandidateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationPrice;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationModificationAccessValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationModificationCandidateValidator;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 개인 수정의 기존 검증·가격·Capacity 연결과 실제 Entity 반영을 검증한다.
 *
 * DB 롤백과 실제 동시성은 추후 검증.
 */
class RegistrationPersonalModificationServiceTest {

    private final RegistrationCapacityService registrationCapacityService =
            mock(RegistrationCapacityService.class);

    private final RegistrationModificationAccessValidator accessValidator =
            mock(RegistrationModificationAccessValidator.class);

    private final RegistrationModificationCandidateValidator candidateValidator =
            mock(RegistrationModificationCandidateValidator.class);

    private final RegistrationModificationPricingService pricingService =
            mock(RegistrationModificationPricingService.class);

    private final ReservationCommandRepository reservationRepository =
            mock(ReservationCommandRepository.class);

    private final CapacityRequirementResolver requirementResolver =
            mock(CapacityRequirementResolver.class);

    private final ReservationCapacityDiffService diffService =
            mock(ReservationCapacityDiffService.class);

    private final CapacityModificationService capacityModificationService =
            mock(CapacityModificationService.class);

    private final RegistrationCommandRepository registrationRepository =
            mock(RegistrationCommandRepository.class);

    private final RegistrationModificationPaymentGuard paymentGuard =
            mock(RegistrationModificationPaymentGuard.class);

    private final RegistrationPersonalModificationService service =
            new RegistrationPersonalModificationService(
                    registrationCapacityService,
                    accessValidator,
                    candidateValidator,
                    pricingService,
                    reservationRepository,
                    requirementResolver,
                    diffService,
                    capacityModificationService,
                    registrationRepository,
                    paymentGuard
            );

    private final LocalDateTime now =
            LocalDateTime.of(2026, 9, 20, 12, 0);

    private Registration registration;
    private EventCategory newCategory;

    private RegistrationModificationRequest request;
    private RegistrationModificationAccessContext access;
    private RegistrationModificationCandidateContext candidate;

    private List<CapacityRequirementDiff> diffs;
    private List<SouvenirJson> normalizedSouvenirs;

    /**
     * 수정 전 Entity와 검증 완료 후보를 별도로 준비한다.
     */
    @BeforeEach
    void setUp() {
        Event event = mock(Event.class);

        when(paymentGuard.lockPersonal("event", "registration"))
                .thenReturn(List.of());

        when(event.getStartDate())
                .thenReturn(LocalDateTime.of(2026, 11, 1, 9, 0));

        EventCategory oldCategory = mock(EventCategory.class);
        newCategory = mock(EventCategory.class);
        when(newCategory.getId()).thenReturn("new-category");

        registration = Registration.builder()
                .id("registration")
                .event(event)
                .eventCategory(oldCategory)
                .name("기존이름")
                .phNum("01011112222")
                .birth("1990-01-01")
                .password("original-password")
                .gender(GenderClass.M)
                .souvenirJson(List.of(new SouvenirJson("shirt", "M")))
                .contractAmount(new BigDecimal("50000"))
                .paidAmount(new BigDecimal("50000"))
                .status(RegistrationStatus.CONFIRMED)
                .version(3L)
                .build();

        request = new RegistrationModificationRequest(
                new RegistrationAccessRequest(
                        "기존이름",
                        "1990-01-01",
                        "01011112222",
                        "original-password"
                ),
                "new-category",
                List.of(new SouvenirJson("shirt", "130")),
                "변경이름",
                "01033334444",
                "2015-01-01",
                GenderClass.F,
                "새 주소",
                "새 상세주소",
                " 보호자 ",
                true
        );

        normalizedSouvenirs =
                List.of(new SouvenirJson("shirt", "130"));

        access = new RegistrationModificationAccessContext(
                event, registration, request, now
        );

        candidate = new RegistrationModificationCandidateContext(
                event,
                registration,
                newCategory,
                normalizedSouvenirs,
                request,
                now
        );

        when(accessValidator.validate(
                "event", "registration", request, now
        )).thenReturn(access);

        when(candidateValidator.validate(access))
                .thenReturn(candidate);

        when(pricingService.repricePersonal(candidate))
                .thenReturn(
                        RegistrationModificationPrice.forExisting(
                                new BigDecimal("50000"),
                                new BigDecimal("40000")
                        )
                );

        Reservation reservation = mock(Reservation.class);
        when(reservation.getId()).thenReturn("reservation");

        when(reservationRepository.findByRegistration_Id("registration"))
                .thenReturn(Optional.of(reservation));

        when(requirementResolver.resolveAll(eq("event"), anyList()))
                .thenReturn(List.of(Map.of("total", 1, "shirt-130", 1)));

        diffs = List.of(mock(CapacityRequirementDiff.class));

        when(diffService.compareAll(anyList(), anyMap()))
                .thenReturn(diffs);
    }

    /**
     * Capacity 이동 이후 검증된 신청정보와 계약금액을 반영한다.
     */
    @Test
    void appliesCandidateAfterCapacityMove() {
        doAnswer(invocation -> {
            assertThat(registration.getName()).isEqualTo("기존이름");
            assertThat(registration.getContractAmount())
                    .isEqualByComparingTo("50000");
            return null;
        }).when(capacityModificationService)
                .moveAll("event", diffs, now);

        var result = service.modify(
                "event", "registration", request, now
        );

        assertThat(registration.getName()).isEqualTo("변경이름");
        assertThat(registration.getPhNum()).isEqualTo("01033334444");
        assertThat(registration.getBirth()).isEqualTo("2015-01-01");
        assertThat(registration.getGender()).isEqualTo(GenderClass.F);
        assertThat(registration.getEventCategory()).isSameAs(newCategory);
        assertThat(registration.getSouvenirJson())
                .containsExactlyElementsOf(normalizedSouvenirs);
        assertThat(registration.getAddress()).isEqualTo("새 주소");
        assertThat(registration.getAddressDetail()).isEqualTo("새 상세주소");
        assertThat(registration.getGuardianName()).isEqualTo("보호자");
        assertThat(registration.isGuardianConsent()).isTrue();

        assertThat(registration.getContractAmount())
                .isEqualByComparingTo("40000");

        // 신청 수정만으로 실제 순결제금액이나 비밀번호를 변경하지 않는다.
        assertThat(registration.getPaidAmount())
                .isEqualByComparingTo("50000");

        assertThat(registration.getPassword())
                .isEqualTo("original-password");

        assertThat(result.price().contractDelta())
                .isEqualByComparingTo("-10000");

        assertThat(result.paidAmount())
                .isEqualByComparingTo("50000");

        InOrder order = inOrder(
                registrationCapacityService,
                paymentGuard,
                accessValidator,
                candidateValidator,
                pricingService,
                capacityModificationService,
                registrationRepository
        );

        order.verify(registrationCapacityService)
                .lockEvent("event");

        order.verify(paymentGuard)
                .lockPersonal("event", "registration");

        order.verify(accessValidator)
                .validate("event", "registration", request, now);

        order.verify(paymentGuard)
                .prepareLockedPayments(List.of());

        order.verify(candidateValidator)
                .validate(access);

        order.verify(pricingService)
                .repricePersonal(candidate);

        order.verify(capacityModificationService)
                .moveAll("event", diffs, now);

        order.verify(registrationRepository)
                .flush();
    }

    /**
     * 자원 이동에 실패하면 실제 신청 후보를 반영하지 않는다.
     */
    @Test
    void capacityFailureLeavesRegistrationUntouched() {
        doThrow(new CustomException(ErrorCode.CAPACITY_ACQUIRE_FAILED))
                .when(capacityModificationService)
                .moveAll("event", diffs, now);

        assertThatThrownBy(
                () -> service.modify(
                        "event", "registration", request, now
                )
        ).isInstanceOf(CustomException.class);

        assertThat(registration.getName()).isEqualTo("기존이름");
        assertThat(registration.getBirth()).isEqualTo("1990-01-01");
        assertThat(registration.getContractAmount())
                .isEqualByComparingTo("50000");

        assertThat(registration.getSouvenirJson())
                .containsExactly(new SouvenirJson("shirt", "M"));

        verify(registrationRepository, never()).flush();
    }

    /**
     * 후보 정책검증 실패 시 가격 계산과 자원 이동으로 진행하지 않는다.
     */
    @Test
    void policyFailureStopsBeforePricingAndCapacity() {
        when(candidateValidator.validate(access))
                .thenThrow(
                        new CustomException(
                                ErrorCode.REGISTRATION_ALREADY_EXISTS
                        )
                );

        assertThatThrownBy(
                () -> service.modify(
                        "event", "registration", request, now
                )
        ).isInstanceOf(CustomException.class);

        verifyNoInteractions(
                pricingService,
                reservationRepository,
                requirementResolver,
                diffService,
                capacityModificationService,
                registrationRepository
        );

        assertThat(registration.getName()).isEqualTo("기존이름");
    }

    /**
     * Payment 충돌이 발생하면 후보 검증과 실제 변경을 시작하지 않는다.
     */
    @Test
    void paymentConflictStopsBeforeCandidateValidation() {
        doThrow(
                new CustomException(
                        ErrorCode.REGISTRATION_MODIFICATION_PAYMENT_CONFLICT
                )
        ).when(paymentGuard).prepareLockedPayments(List.of());

        assertThatThrownBy(
                () -> service.modify(
                        "event", "registration", request, now
                )
        ).isInstanceOf(CustomException.class);

        verifyNoInteractions(
                candidateValidator,
                pricingService,
                reservationRepository,
                requirementResolver,
                diffService,
                capacityModificationService,
                registrationRepository
        );

        assertThat(registration.getName()).isEqualTo("기존이름");
    }

    /**
     * Payment 잠금 이후 본인확인하며, 실패하면 주문 무효화로 진행하지 않는다.
     */
    @Test
    void accessFailureDoesNotInvalidatePayments() {
        when(accessValidator.validate(
                "event", "registration", request, now
        )).thenThrow(
                new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED)
        );

        assertThatThrownBy(
                () -> service.modify(
                        "event", "registration", request, now
                )
        ).isInstanceOf(CustomException.class);

        verify(paymentGuard).lockPersonal("event", "registration");
        verify(paymentGuard, never()).prepareLockedPayments(anyList());

        verifyNoInteractions(
                candidateValidator,
                pricingService,
                capacityModificationService
        );
    }
}