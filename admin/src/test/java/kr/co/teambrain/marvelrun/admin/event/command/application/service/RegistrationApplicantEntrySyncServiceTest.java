package kr.co.teambrain.marvelrun.admin.event.command.application.service;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.admin.event.command.repository.RegistrationEntryCommandRepository;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 관리자 신청자 수정 뒤 단품 참가 항목을 신청자 값으로 맞추는지 확인한다. */
@ExtendWith(MockitoExtension.class)
class RegistrationApplicantEntrySyncServiceTest {

    @Mock
    private RegistrationEntryCommandRepository entries;

    @InjectMocks
    private RegistrationApplicantEntrySyncService service;

    private final Event event = mock(Event.class);

    /** 단품 항목은 바뀐 신청자 값을 따르고 배번은 그대로 둔다. */
    @Test
    void followsApplicantKeepingBib() {
        EventCategory single = category(ProductType.SINGLE);
        Registration registration = registration(single);
        RegistrationEntry entry = RegistrationEntry.builder().event(event).registration(registration).eventCategory(single)
                .name("기존").phNum("010-0000-0000").birth("1990-01-01").gender(GenderClass.M).bibNumber("10001").build();
        when(entries.findByRegistration_Id("r")).thenReturn(List.of(entry));

        service.syncApplicantEntry(registration);

        assertThat(entry.getName()).isEqualTo("정정이름");
        assertThat(entry.getPhNum()).isEqualTo("010-9999-9999");
        assertThat(entry.getBirth()).isEqualTo("1991-02-03");
        assertThat(entry.getGender()).isEqualTo(GenderClass.F);
        assertThat(entry.getBibNumber()).isEqualTo("10001");
    }

    /** 단품인데 항목이 없으면 신청자 값으로 만든다. */
    @Test
    void createsMissingSingleEntry() {
        EventCategory single = category(ProductType.SINGLE);
        Registration registration = registration(single);
        when(entries.findByRegistration_Id("r")).thenReturn(List.of());

        service.syncApplicantEntry(registration);

        ArgumentCaptor<RegistrationEntry> saved = ArgumentCaptor.forClass(RegistrationEntry.class);
        verify(entries).save(saved.capture());
        assertThat(saved.getValue().getRegistration()).isSameAs(registration);
        assertThat(saved.getValue().getEventCategory()).isSameAs(single);
        assertThat(saved.getValue().getName()).isEqualTo("정정이름");
        assertThat(saved.getValue().getBibNumber()).isNull();
    }

    /** 패키지·기존 종목은 신청자 수정으로 항목을 바꾸지 않는다. */
    @Test
    void ignoresNonSingleCategories() {
        service.syncApplicantEntry(registration(category(ProductType.PACKAGE)));
        service.syncApplicantEntry(registration(category(null)));

        verifyNoInteractions(entries);
    }

    private Registration registration(EventCategory category) {
        return Registration.builder().id("r").event(event).eventCategory(category)
                .name("정정이름").phNum("010-9999-9999").birth("1991-02-03").gender(GenderClass.F).build();
    }

    private static EventCategory category(ProductType productType) {
        EventCategory category = mock(EventCategory.class);
        when(category.getProductType()).thenReturn(productType);
        return category;
    }
}
