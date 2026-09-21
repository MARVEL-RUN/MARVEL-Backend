package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityRequirementDiff;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.ReservationAllocation;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationItemCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 예약 상태별 카운터 선택과 증가분 확보 이후의 반환 순서를 검증한다.
 *
 * 실제 트랜잭션 롤백과 @Version 충돌은 DB 테스트에서 별도로 검증한다.
 */
class CapacityModificationServiceTest {

    private final CapacityCommandRepository capacityRepository =
            mock(CapacityCommandRepository.class);

    private final ReservationCommandRepository reservationRepository =
            mock(ReservationCommandRepository.class);

    private final ReservationItemCommandRepository itemRepository =
            mock(ReservationItemCommandRepository.class);

    private final CapacityModificationService service =
            new CapacityModificationService(
                    capacityRepository,
                    reservationRepository,
                    itemRepository,
                    new ReservationCapacityDiffService(itemRepository)
            );

    private final LocalDateTime now =
            LocalDateTime.of(2026, 9, 20, 12, 0);

    /**
     * HELD와 CONSUMED 각각에 대해 증가분 확보 후 감소분을 반환한다.
     * 공통 자원은 추가 확보하거나 반환하지 않는다.
     */
    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"HELD", "CONSUMED"}
    )
    void acquiresBeforeReleaseAndUsesMatchingCounter(
            ReservationStatus status
    ) {
        prepareReservation(status, 3L);

        when(itemRepository.findAllocations(anyCollection()))
                .thenReturn(List.of(
                        new ReservationAllocation("r1", "old", 1),
                        new ReservationAllocation("r1", "total", 1)
                ));

        if (status == ReservationStatus.HELD) {
            when(capacityRepository.acquireHeld("event", "new", 1, now))
                    .thenReturn(1);
            when(capacityRepository.releaseHeld("event", "old", 1, now))
                    .thenReturn(1);
        } else {
            when(capacityRepository.acquireConfirmed("event", "new", 1, now))
                    .thenReturn(1);
            when(capacityRepository.releaseConfirmed("event", "old", 1, now))
                    .thenReturn(1);
        }

        service.moveAll("event", List.of(diff(status, 3L)), now);

        InOrder order = inOrder(
                reservationRepository,
                capacityRepository,
                itemRepository
        );

        order.verify(reservationRepository).flush();

        if (status == ReservationStatus.HELD) {
            order.verify(capacityRepository)
                    .acquireHeld("event", "new", 1, now);

            order.verify(capacityRepository)
                    .releaseHeld("event", "old", 1, now);

            verify(capacityRepository, never())
                    .acquireConfirmed(anyString(), anyString(), anyInt(), any());

            verify(capacityRepository, never())
                    .releaseConfirmed(anyString(), anyString(), anyInt(), any());
        } else {
            order.verify(capacityRepository)
                    .acquireConfirmed("event", "new", 1, now);

            order.verify(capacityRepository)
                    .releaseConfirmed("event", "old", 1, now);

            verify(capacityRepository, never())
                    .acquireHeld(anyString(), anyString(), anyInt(), any());

            verify(capacityRepository, never())
                    .releaseHeld(anyString(), anyString(), anyInt(), any());
        }

        order.verify(itemRepository)
                .deleteAllByReservationIds(List.of("r1"));

        verify(capacityRepository, never())
                .acquireHeld(eq("event"), eq("total"), anyInt(), any());

        verify(capacityRepository, never())
                .releaseHeld(eq("event"), eq("total"), anyInt(), any());

        verify(capacityRepository, never())
                .acquireConfirmed(eq("event"), eq("total"), anyInt(), any());

        verify(capacityRepository, never())
                .releaseConfirmed(eq("event"), eq("total"), anyInt(), any());

        verify(itemRepository, times(2)).save(any());
        verify(itemRepository).flush();
    }

    /**
     * 증가분 확보 실패 시 기존 수량 반환과 상세 삭제로 진행하지 않는다.
     */
    @Test
    void acquireFailureStopsBeforeReleaseAndReplacement() {
        prepareReservation(ReservationStatus.HELD, 3L);

        when(itemRepository.findAllocations(anyCollection()))
                .thenReturn(List.of(
                        new ReservationAllocation("r1", "old", 1),
                        new ReservationAllocation("r1", "total", 1)
                ));

        when(capacityRepository.acquireHeld("event", "new", 1, now))
                .thenReturn(0);

        assertThatThrownBy(
                () -> service.moveAll(
                        "event",
                        List.of(diff(ReservationStatus.HELD, 3L)),
                        now
                )
        )
                .isInstanceOf(CustomException.class)
                .satisfies(exception ->
                        assertThat(((CustomException) exception).getErrorCode())
                                .isEqualTo(ErrorCode.CAPACITY_ACQUIRE_FAILED)
                );

        verify(capacityRepository, never())
                .releaseHeld(anyString(), anyString(), anyInt(), any());

        verify(itemRepository, never())
                .deleteAllByReservationIds(anyCollection());

        verify(itemRepository, never()).save(any());
    }

    /**
     * Diff 계산 당시 버전과 현재 예약 버전이 다르면 카운터를 변경하지 않는다.
     */
    @Test
    void rejectsStaleVersion() {
        prepareReservation(ReservationStatus.HELD, 4L);

        assertThatThrownBy(
                () -> service.moveAll(
                        "event",
                        List.of(diff(ReservationStatus.HELD, 3L)),
                        now
                )
        )
                .isInstanceOf(CustomException.class)
                .satisfies(exception ->
                        assertThat(((CustomException) exception).getErrorCode())
                                .isEqualTo(ErrorCode.RESERVATION_STATE_CONFLICT)
                );

        verifyNoInteractions(capacityRepository, itemRepository);
        verify(reservationRepository, never()).flush();
    }

    /**
     * 전달된 old 수량이 실제 상세와 다르면 카운터 변경 전에 차단한다.
     */
    @Test
    void rejectsDiffThatDoesNotMatchStoredItems() {
        prepareReservation(ReservationStatus.HELD, 3L);

        when(itemRepository.findAllocations(anyCollection()))
                .thenReturn(List.of(
                        new ReservationAllocation("r1", "old", 2),
                        new ReservationAllocation("r1", "total", 1)
                ));

        assertThatThrownBy(
                () -> service.moveAll(
                        "event",
                        List.of(diff(ReservationStatus.HELD, 3L)),
                        now
                )
        )
                .isInstanceOf(CustomException.class)
                .satisfies(exception ->
                        assertThat(((CustomException) exception).getErrorCode())
                                .isEqualTo(ErrorCode.RESERVATION_STATE_CONFLICT)
                );

        verifyNoInteractions(capacityRepository);

        verify(itemRepository, never())
                .deleteAllByReservationIds(anyCollection());
    }

    /**
     * 실제 이력 변경이 가능한 예약과 대회 귀속 참조를 구성한다.
     */
    private void prepareReservation(
            ReservationStatus status,
            long version
    ) {
        Registration registration =
                mock(Registration.class, RETURNS_DEEP_STUBS);

        when(registration.getEvent().getId()).thenReturn("event");

        Reservation reservation =
                Reservation.builder()
                        .id("r1")
                        .registration(registration)
                        .status(status)
                        .version(version)
                        .build();

        when(reservationRepository.findById("r1"))
                .thenReturn(Optional.of(reservation));
    }

    /**
     * 공통 자원 하나를 유지하고 기존 자원을 새 자원으로 바꾸는 Diff이다.
     */
    private CapacityRequirementDiff diff(
            ReservationStatus status,
            long version
    ) {
        return new CapacityRequirementDiff(
                "r1",
                status,
                version,
                List.of(
                        new CapacityRequirementDiff.Item(
                                "new", 0, 1, 1, 0, 0
                        ),
                        new CapacityRequirementDiff.Item(
                                "old", 1, 0, 0, 1, 0
                        ),
                        new CapacityRequirementDiff.Item(
                                "total", 1, 1, 0, 0, 1
                        )
                )
        );
    }
}