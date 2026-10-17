package kr.co.teambrain.marvelrun.admin.discount.query;

import kr.co.teambrain.marvelrun.admin.discount.domain.ReferenceFilter;
import kr.co.teambrain.marvelrun.admin.discount.repository.ReferenceFilterRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 관리자 선택지 정렬·명시 응답과 참가자 목록의 일괄 조회를 검증한다. */
class ReferenceFilterQueryServiceTest {
    private final ReferenceFilterRepository repository = mock(ReferenceFilterRepository.class);
    private final ReferenceFilterQueryService service = new ReferenceFilterQueryService(repository);

    /** 노출 우선순위를 정렬 키로 혼용하지 않고 안정적인 정렬과 페이지를 전달한다. */
    @Test
    void preservesDisplayOrderingAndPage() {
        ReferenceFilter filter = ReferenceFilter.builder().id("filter").eventId("event").code("INJE")
            .displayName("인제군민").priority(10).sortOrder(20).build();
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(filter)));
        Page<ReferenceFilterSummaryResponse> result = service.getEventReferenceFilters("event", 10, "INJE", 0, 50);
        assertThat(result.getContent().getFirst().code()).isEqualTo("INJE");
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(50);
        assertThat(page.getValue().getSort()).extracting(order -> order.getProperty()).containsExactly("sortOrder", "code", "id");
    }

    /** 미부여 인원과 중복 필터 ID를 제거하고 일괄 조회한다. */
    @Test
    void batchesOnlyAssignedFilterIds() {
        service.loadReferenceFilterSummaries(Arrays.asList(null, "filter", "filter"));
        verify(repository).findAllById(List.of("filter"));
    }
}
