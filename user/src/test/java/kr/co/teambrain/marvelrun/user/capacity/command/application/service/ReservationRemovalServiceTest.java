package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.ReservationAllocation;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationItemCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 제거 시 예약 상태별 반환 및 금융 추적 정보 보존을 검증한다.
 *
 * 실제 DB 롤백과 동시성은 검증하지않음.
 */
class ReservationRemovalServiceTest {

    private final ReservationCommandRepository reservationRepository =
            mock(ReservationCommandRepository.class);

    private final ReservationItemCommandRepository itemRepository =
            mock(ReservationItemCommandRepository.class);

    private final CapacityCommandRepository capacityRepository =
            mock(CapacityCommandRepository.class);

    private final ReservationRemovalService service =
            new ReservationRemovalService(
                    reservationRepository,
                    itemRepository,
                    capacityRepository
            );

    private final LocalDateTime now =
            LocalDateTime.of(2026, 9, 20, 12, 0);

    /**
     * 같은 Capacity라도 홀딩과 확정 수량을 구분하여 반환한다.
     */
    @Test
    void releasesHeldAndConfirmedSeparately() {
        Reservation held = reservation("r1", "g1", ReservationStatus.HELD);
        Reservation consumed =
                reservation("r2", "g2", ReservationStatus.CONSUMED);

        when(reservationRepository.findAllByRegistrationIds(anyCollection()))
                .thenReturn(List.of(consumed, held));

        when(itemRepository.findAllocations(anyCollection()))
                .thenReturn(List.of(
                        new ReservationAllocation("r1", "total", 1),
                        new ReservationAllocation("r2", "total", 1)
                ));

        when(capacityRepository.releaseHeld("event", "total", 1, now))
                .thenReturn(1);

        when(capacityRepository.releaseConfirmed("event", "total", 1, now))
                .thenReturn(1);

        service.releaseAll("event", List.of("g1", "g2"), now);

        assertThat(held.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(consumed.getStatus()).isEqualTo(ReservationStatus.RELEASED);

        verify(capacityRepository).releaseHeld("event", "total", 1, now);
        verify(capacityRepository).releaseConfirmed("event", "total", 1, now);

        verify(itemRepository, never())
                .deleteAllByReservationIds(anyCollection());
    }

    /**
     * 이미 반환한 예약의 과거 상세를 다시 반환하지 않는다.
     */
    @Test
    void releasedReservationDoesNotReleaseTwice() {
        Reservation released =
                reservation(
                        "r1",
                        "g1",
                        ReservationStatus.RELEASED
                );

        when(reservationRepository.findAllByRegistrationIds(anyCollection()))
                .thenReturn(List.of(released));

        service.releaseAll("event", List.of("g1"), now);

        verifyNoInteractions(itemRepository, capacityRepository);
        verify(reservationRepository, never()).flush();
    }

    /**
     * 결제 처리 중인 예약이 하나라도 있으면 반환을 시작하지 않는다.
     */
    @Test
    void rejectsProcessingBeforeChangingReservations() {
        Reservation held = reservation("r1", "g1", ReservationStatus.HELD);
        Reservation processing =
                reservation("r2", "g2", ReservationStatus.PROCESSING);

        when(reservationRepository.findAllByRegistrationIds(anyCollection()))
                .thenReturn(List.of(held, processing));

        assertThatThrownBy(
                () -> service.releaseAll("event", List.of("g1", "g2"), now)
        ).isInstanceOf(CustomException.class);

        assertThat(held.getStatus()).isEqualTo(ReservationStatus.HELD);
        verifyNoInteractions(itemRepository, capacityRepository);
        verify(reservationRepository, never()).flush();
    }

    /**
     * 점유 상태인데 저장된 상세가 없으면 정상 반환으로 간주하지 않는다.
     */
    @Test
    void rejectsMissingAllocation() {
        Reservation held =
                reservation(
                        "r1",
                        "g1",
                        ReservationStatus.HELD
                );

        when(reservationRepository.findAllByRegistrationIds(anyCollection()))
                .thenReturn(List.of(held));

        when(itemRepository.findAllocations(anyCollection()))
                .thenReturn(List.of());

        assertThatThrownBy(
                () -> service.releaseAll(
                        "event",
                        List.of("g1"),
                        now
                )
        )
                .isInstanceOf(CustomException.class)
                .satisfies(exception ->
                        assertThat(
                                ((CustomException) exception).getErrorCode()
                        ).isEqualTo(ErrorCode.CAPACITY_COUNTER_MISMATCH)
                );

        verifyNoInteractions(capacityRepository);
    }

    /**
     * 결제 완료 구성원을 제거해도 환불할 금액과 단체 귀속을 보존한다.
     */
    @Test
    void removalPreservesPaidAmountAndOrganization() {
        Organization organization = mock(Organization.class);

        Registration registration = Registration.builder()
                .id("g1")
                .organization(organization)
                .contractAmount(new BigDecimal("50000"))
                .paidAmount(new BigDecimal("50000"))
                .status(RegistrationStatus.CONFIRMED)
                .build();

        registration.removeFromOrganization();

        assertThat(registration.isSoftDeleted()).isTrue();
        assertThat(registration.getContractAmount())
                .isEqualByComparingTo("0");
        assertThat(registration.getPaidAmount())
                .isEqualByComparingTo("50000");
        assertThat(registration.getOrganization()).isSameAs(organization);
        assertThat(registration.getStatus())
                .isEqualTo(RegistrationStatus.CANCELLATION_PENDING);
    }

    /**
     * 미결제 구성원 제거는 환불 대기 없이 참가 취소 상태가 된다.
     */
    @Test
    void unpaidRemovalCompletesCancellation() {
        Registration registration = Registration.builder()
                .organization(mock(Organization.class))
                .contractAmount(new BigDecimal("50000"))
                .paidAmount(BigDecimal.ZERO)
                .status(RegistrationStatus.PAYMENT_PENDING)
                .build();

        registration.removeFromOrganization();

        assertThat(registration.isSoftDeleted()).isTrue();
        assertThat(registration.getStatus())
                .isEqualTo(RegistrationStatus.CANCELED);
    }

    /**
     * 대회 귀속을 가진 실제 예약 객체를 구성한다.
     */
    private Reservation reservation(
            String reservationId,
            String registrationId,
            ReservationStatus status
    ) {
        Registration registration =
                mock(Registration.class, RETURNS_DEEP_STUBS);

        when(registration.getId()).thenReturn(registrationId);
        when(registration.getEvent().getId()).thenReturn("event");

        return Reservation.builder()
                .id(reservationId)
                .registration(registration)
                .status(status)
                .version(3L)
                .build();
    }
}