package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityRequirementDiff;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.ReservationAllocation;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationItemCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * 실제 확보 상세를 old 기준으로 사용하고 예약별 차이를 보존하는지 확인한다.
 */
class ReservationCapacityDiffServiceTest {

    /**
     * 공통 정원을 재확보하지 않고 종목·사이즈 변경분만 계산한다.
     */
    @Test
    void calculatesChangesFromStoredItems() {
        ReservationItemCommandRepository repository =
                mock(ReservationItemCommandRepository.class);

        when(repository.findAllocations(Set.of("r1")))
                .thenReturn(List.of(
                        new ReservationAllocation("r1", "total", 1),
                        new ReservationAllocation("r1", "old-category", 1),
                        new ReservationAllocation("r1", "shirt-m", 1)
                ));

        var service = new ReservationCapacityDiffService(repository);

        var result = service.compareAll(
                List.of(reservation("r1", ReservationStatus.HELD)),
                Map.of(
                        "r1",
                        Map.of(
                                "total", 1,
                                "new-category", 1,
                                "shirt-130", 1
                        )
                )
        ).get(0);

        assertThat(result.items()).containsExactly(
                new CapacityRequirementDiff.Item(
                        "new-category", 0, 1, 1, 0, 0
                ),
                new CapacityRequirementDiff.Item(
                        "old-category", 1, 0, 0, 1, 0
                ),
                new CapacityRequirementDiff.Item(
                        "shirt-130", 0, 1, 1, 0, 0
                ),
                new CapacityRequirementDiff.Item(
                        "shirt-m", 1, 0, 0, 1, 0
                ),
                new CapacityRequirementDiff.Item(
                        "total", 1, 1, 0, 0, 1
                )
        );

        assertThat(result.reservationStatus())
                .isEqualTo(ReservationStatus.HELD);
        assertThat(result.reservationVersion()).isEqualTo(3L);

        verify(repository).findAllocations(Set.of("r1"));
        verifyNoMoreInteractions(repository);
    }

    /**
     * 단체의 HELD·CONSUMED 예약을 합쳐 상쇄하지 않고 각각 계산한다.
     */
    @Test
    void keepsHeldAndConsumedDiffsSeparate() {
        ReservationItemCommandRepository repository =
                mock(ReservationItemCommandRepository.class);

        when(repository.findAllocations(Set.of("r1", "r2")))
                .thenReturn(List.of(
                        new ReservationAllocation("r1", "capacity", 2),
                        new ReservationAllocation("r2", "capacity", 1)
                ));

        var service = new ReservationCapacityDiffService(repository);

        var results = service.compareAll(
                List.of(
                        reservation("r1", ReservationStatus.HELD),
                        reservation("r2", ReservationStatus.CONSUMED)
                ),
                Map.of(
                        "r1", Map.of("capacity", 1),
                        "r2", Map.of("capacity", 2)
                )
        );

        assertThat(results.get(0).items()).containsExactly(
                new CapacityRequirementDiff.Item(
                        "capacity", 2, 1, 0, 1, 1
                )
        );

        assertThat(results.get(1).items()).containsExactly(
                new CapacityRequirementDiff.Item(
                        "capacity", 1, 2, 1, 0, 1
                )
        );

        assertThat(results.get(1).reservationStatus())
                .isEqualTo(ReservationStatus.CONSUMED);

        verify(repository, times(1)).findAllocations(Set.of("r1", "r2"));
    }

    /**
     * 반환된 예약의 남은 상세나 승인 진행 중 상세를 이동 기준으로 쓰지 않는다.
     */
    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"RELEASED", "PROCESSING"}
    )
    void rejectsUnsupportedReservationState(ReservationStatus status) {
        ReservationItemCommandRepository repository =
                mock(ReservationItemCommandRepository.class);

        var service = new ReservationCapacityDiffService(repository);

        assertThatThrownBy(
                () -> service.compareAll(
                        List.of(reservation("r1", status)),
                        Map.of("r1", Map.of("total", 1))
                )
        )
                .isInstanceOf(CustomException.class)
                .satisfies(exception ->
                        assertThat(((CustomException) exception).getErrorCode())
                                .isEqualTo(ErrorCode.RESERVATION_STATE_CONFLICT)
                );

        verifyNoInteractions(repository);
    }

    /**
     * 점유 중인 예약의 상세가 누락되면 old=0으로 간주하지 않는다.
     */
    @Test
    void rejectsMissingStoredItems() {
        ReservationItemCommandRepository repository =
                mock(ReservationItemCommandRepository.class);

        when(repository.findAllocations(Set.of("r1")))
                .thenReturn(List.of());

        var service = new ReservationCapacityDiffService(repository);

        assertThatThrownBy(
                () -> service.compareAll(
                        List.of(reservation("r1", ReservationStatus.HELD)),
                        Map.of("r1", Map.of("total", 1))
                )
        )
                .isInstanceOf(CustomException.class)
                .satisfies(exception ->
                        assertThat(((CustomException) exception).getErrorCode())
                                .isEqualTo(ErrorCode.RESERVATION_STATE_CONFLICT)
                );
    }

    /**
     * 조회 시점의 상태와 버전을 가진 실제 예약 객체를 구성한다.
     */
    private Reservation reservation(
            String id,
            ReservationStatus status
    ) {
        return Reservation.builder()
                .id(id)
                .status(status)
                .version(3L)
                .build();
    }
}