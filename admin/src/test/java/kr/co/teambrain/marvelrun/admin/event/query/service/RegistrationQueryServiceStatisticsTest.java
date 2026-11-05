package kr.co.teambrain.marvelrun.admin.event.query.service;

import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationStatDto;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationQueryRepository;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventFormat;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** 버추얼 대회의 통계는 아동 구분을 비우고, 그 밖의 경우(대회 없음 포함)는 기존 집계를 유지한다. */
@ExtendWith(MockitoExtension.class)
class RegistrationQueryServiceStatisticsTest {

    @Mock RegistrationQueryRepository registrationQueryRepository;
    @InjectMocks RegistrationQueryService service;

    private void stats() {
        BigDecimal amount = new BigDecimal("40000");
        when(registrationQueryRepository.findStatsByEventId("event")).thenReturn(List.of(
                new RegistrationStatDto(RegistrationStatus.CONFIRMED, null, GenderClass.M,
                        "2014-01-01", "A", amount, amount, PaymentMethod.CARD)));
    }

    @Test
    void virtualEventHasNoChildStats() {
        stats();
        when(registrationQueryRepository.findEventFormatById("event")).thenReturn(EventFormat.VIRTUAL);

        var response = service.getEventStatistics("event");

        assertThat(response.childStats()).isEmpty();
        assertThat(response.genderStats()).isNotEmpty();
        assertThat(response.ageGroupStats()).isNotEmpty();
    }

    @Test
    void offlineEventKeepsChildStats() {
        stats();
        when(registrationQueryRepository.findEventFormatById("event")).thenReturn(EventFormat.OFFLINE);

        assertThat(service.getEventStatistics("event").childStats()).hasSize(6);
    }

    @Test
    void missingEventBehavesAsOffline() {
        stats();
        when(registrationQueryRepository.findEventFormatById("event")).thenReturn(null);

        var childStats = service.getEventStatistics("event").childStats();

        assertThat(childStats).hasSize(6);
        assertThat(childStats.stream().filter(row -> row.classification().equals("신청자(아동)"))
                .findFirst().orElseThrow().totalCount()).isEqualTo(1);
    }
}
