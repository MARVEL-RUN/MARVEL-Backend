package kr.co.teambrain.marvelrun.admin.capacity.command.application.service;

import kr.co.teambrain.marvelrun.admin.capacity.command.application.domain.Capacity;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.dto.CapacityShortage;
import kr.co.teambrain.marvelrun.admin.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.repository.EventCommandRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 엑셀에 독립적인 전체 자원 필요량과 최종 잠금 검증을 확인한다. */
class RegistrationCapacityServiceTest {
    private final CapacityCommandRepository capacities = mock(CapacityCommandRepository.class);
    private final EventCommandRepository events = mock(EventCommandRepository.class);
    private final RegistrationCapacityService service = new RegistrationCapacityService(events, capacities);

    /** 여러 참가자가 공유하는 자원의 필요량을 합산한다. */
    @Test
    void sumsSharedCapacityRequirementsBeforeCheckingAvailability() {
        Capacity capacity = capacity(10, 5, 4, true);
        when(capacities.findAllById(any())).thenReturn(List.of(capacity));
        assertThat(service.findRegistrationCapacityShortages("event", List.of(Map.of("capacity", 1), Map.of("capacity", 1)), false))
                .containsExactly(new CapacityShortage("capacity", 2, 1));
        verify(capacities, never()).acquireHeld(any(), any(), anyInt(), any());
    }

    /** 최종 확인은 최신 잠금 조회를 사용하며 남은 수량과 정확히 같으면 허용한다. */
    @Test
    void usesLockedRowsForFinalCapacityCheck() {
        // 내부 mock 설정을 끝낸 뒤 Repository 반환값을 설정한다.
        Capacity capacity = capacity(10, 5, 3, true);
        when(capacities.findAllForUpdate(eq("event"), any())).thenReturn(List.of(capacity));
        assertThat(service.findRegistrationCapacityShortages("event", List.of(Map.of("capacity", 2)), true)).isEmpty();
        verify(capacities).findAllForUpdate("event", Set.of("capacity"));
        verify(capacities, never()).findAllById(any());
    }

    /** 비활성 자원은 온라인 신규 확보와 동일하게 사용할 수 없다. */
    @Test
    void rejectsInactiveCapacityEvenWhenCountRemains() {
        // thenReturn 인자 평가 중 다른 when 호출이 중첩되지 않게 분리한다.
        Capacity capacity = capacity(10, 0, 0, false);
        when(capacities.findAllById(any())).thenReturn(List.of(capacity));
        assertThat(service.findRegistrationCapacityShortages("event", List.of(Map.of("capacity", 1)), false))
                .containsExactly(new CapacityShortage("capacity", 1, 0));
    }

    /** 현재 DB 스냅샷 역할의 자원 객체를 구성한다. */
    private Capacity capacity(int limit, int held, int confirmed, boolean active) {
        Event event = mock(Event.class);
        when(event.getId()).thenReturn("event");
        Capacity capacity = mock(Capacity.class);
        when(capacity.getId()).thenReturn("capacity");
        when(capacity.getEvent()).thenReturn(event);
        when(capacity.getLimitCount()).thenReturn(limit);
        when(capacity.getHeldCount()).thenReturn(held);
        when(capacity.getConfirmedCount()).thenReturn(confirmed);
        when(capacity.isActive()).thenReturn(active);
        return capacity;
    }
}
