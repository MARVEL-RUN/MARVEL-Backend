package kr.co.teambrain.marvelrun.admin.event.query.service;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.admin.event.query.dto.response.RegistrationEntryListResponse;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationEntryQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationQueryRepository;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventFormat;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 참가 항목 조회의 수정 가능 여부와 항목 변환, 없는 신청 거절을 확인한다. */
@ExtendWith(MockitoExtension.class)
class RegistrationEntryQueryServiceTest {

    @Mock RegistrationQueryRepository registrations;
    @Mock RegistrationEntryQueryRepository entries;

    private RegistrationEntryQueryService service;
    private Registration registration;
    private EventCategory category;

    @BeforeEach
    void setUp() {
        service = new RegistrationEntryQueryService(registrations, entries);
    }

    /** reg-1이 주어진 상품 유형의 종목과 대회 진행 방식을 갖도록 맞춘다. */
    private void stubRegistration(ProductType productType, EventFormat format) {
        Event event = mock(Event.class);
        when(event.getId()).thenReturn("event-1");
        category = mock(EventCategory.class);
        when(category.getProductType()).thenReturn(productType);
        registration = mock(Registration.class);
        when(registration.getEventCategory()).thenReturn(category);
        when(registration.getEvent()).thenReturn(event);
        when(registrations.findById("reg-1")).thenReturn(Optional.of(registration));
        when(registrations.findEventFormatById("event-1")).thenReturn(format);
    }

    @Test
    void 패키지는_항목_셋과_수정_가능을_돌려준다() {
        stubRegistration(ProductType.PACKAGE, EventFormat.VIRTUAL);
        List<RegistrationEntry> rows = List.of(entry("e1", "c1", "5km", "10001"),
                entry("e2", "c2", "10km", "20001"), entry("e3", "c3", "3km", "30001"));
        when(entries.findByRegistrationIds(List.of("reg-1"))).thenReturn(rows);

        RegistrationEntryListResponse response = service.getEntries("reg-1");

        assertThat(response.productType()).isEqualTo(ProductType.PACKAGE);
        assertThat(response.editable()).isTrue();
        assertThat(response.entries()).extracting(RegistrationEntryListResponse.Entry::entryId).containsExactly("e1", "e2", "e3");
        assertThat(response.entries()).extracting(RegistrationEntryListResponse.Entry::bibNumber).containsExactly("10001", "20001", "30001");
        assertThat(response.entries().getFirst().categoryName()).isEqualTo("5km");
        assertThat(response.entries().getFirst().name()).isEqualTo("받는사람");
    }

    @Test
    void 단품은_항목_하나이고_수정_불가다() {
        stubRegistration(ProductType.SINGLE, EventFormat.VIRTUAL);
        List<RegistrationEntry> rows = List.of(entry("e1", "c1", "5km", "10001"));
        when(entries.findByRegistrationIds(List.of("reg-1"))).thenReturn(rows);

        RegistrationEntryListResponse response = service.getEntries("reg-1");

        assertThat(response.productType()).isEqualTo(ProductType.SINGLE);
        assertThat(response.editable()).isFalse();
        assertThat(response.entries()).hasSize(1);
    }

    @Test
    void 오프라인_대회는_항목이_비어_있고_수정_불가다() {
        stubRegistration(null, EventFormat.OFFLINE);

        RegistrationEntryListResponse response = service.getEntries("reg-1");

        assertThat(response.editable()).isFalse();
        assertThat(response.entries()).isEmpty();
    }

    @Test
    void 없는_신청은_거절한다() {
        when(registrations.findById("none")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getEntries("none"))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REGISTRATION_NOT_FOUND));
    }

    private RegistrationEntry entry(String id, String categoryId, String categoryName, String bib) {
        EventCategory entryCategory = mock(EventCategory.class);
        when(entryCategory.getId()).thenReturn(categoryId);
        when(entryCategory.getName()).thenReturn(categoryName);
        RegistrationEntry entry = mock(RegistrationEntry.class);
        when(entry.getId()).thenReturn(id);
        when(entry.getEventCategory()).thenReturn(entryCategory);
        when(entry.getName()).thenReturn("받는사람");
        when(entry.getPhNum()).thenReturn("010-0000-0000");
        when(entry.getBirth()).thenReturn("1990-01-01");
        when(entry.getGender()).thenReturn(GenderClass.M);
        when(entry.getBibNumber()).thenReturn(bib);
        when(entry.getBibIssuedAt()).thenReturn(LocalDateTime.of(2026, 11, 1, 12, 0));
        return entry;
    }
}
