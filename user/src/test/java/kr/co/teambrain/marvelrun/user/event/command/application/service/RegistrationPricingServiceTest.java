package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/*
*
개인/단체 공용 Pricing 계산점 검증
대상 Event 어린이 40,000원
2013-09-10 경계 비적용
2013-09-11 경계 적용
더 어린 참가자 40,000원
대상 Event 성인 기본가격
다른 Event 어린이 기본가격
*
* */


/**
 * 9/22 MarvelRun MVP의 임시 참가비 계산 규칙을 검증한다.
 *
 * 대상 MarvelRun Event에만 어린이 40,000원 고정가격이 적용되는지,
 * 대회일 기준 어린이 경계와 일반 참가비 유지 여부를 확인한다.
 */
class RegistrationPricingServiceTest {

    private static final LocalDateTime EVENT_START_DATE =
            LocalDateTime.of(2026, 9, 10, 11, 0);

    private final RegistrationPricingService registrationPricingService =
            new RegistrationPricingService();


    /**
     * 대상 Event에서 대회일 기준 어린이면 40,000원을 반환하는지 검증한다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"test-marvelrun", "marvelrun2026"})
    void targetEventChildUsesFixedPrice(String targetEventId) {

        Event event =
                createEvent(
                        targetEventId,
                        EVENT_START_DATE
                );

        EventCategory eventCategory =
                createCategory(
                        new BigDecimal("70000")
                );

        BigDecimal result =
                registrationPricingService.calculateContractAmount(
                        event,
                        eventCategory,
                        "2013-09-11"
                );

        assertThat(result)
                .isEqualByComparingTo("40000");
    }


    /**
     * 어린이 경계 바로 전날 출생자는 대회일에 이미 만 13세이므로
     * Category 기본 참가비를 사용하는지 검증한다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"test-marvelrun", "marvelrun2026"})
    void thirteenthBirthdayOnEventDateUsesBasePrice(String targetEventId) {

        Event event =
                createEvent(
                        targetEventId,
                        EVENT_START_DATE
                );

        EventCategory eventCategory =
                createCategory(
                        new BigDecimal("70000")
                );

        BigDecimal result =
                registrationPricingService.calculateContractAmount(
                        event,
                        eventCategory,
                        "2013-09-10"
                );

        assertThat(result)
                .isEqualByComparingTo("70000");
    }


    /**
     * 어린이 경계보다 더 어린 참가자에게도 동일한 40,000원
     * 고정가격이 적용되는지 검증한다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"test-marvelrun", "marvelrun2026"})
    void youngerChildUsesFixedPrice(String targetEventId) {

        Event event =
                createEvent(
                        targetEventId,
                        EVENT_START_DATE
                );

        EventCategory eventCategory =
                createCategory(
                        new BigDecimal("55000")
                );

        BigDecimal result =
                registrationPricingService.calculateContractAmount(
                        event,
                        eventCategory,
                        "2020-01-01"
                );

        assertThat(result)
                .isEqualByComparingTo("40000");
    }


    /**
     * 대상 Event의 성인 참가자는 임시 어린이 가격정책의 영향을 받지 않고
     * Category 기본 참가비를 그대로 사용하는지 검증한다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"test-marvelrun", "marvelrun2026"})
    void targetEventAdultUsesBasePrice(String targetEventId) {

        Event event =
                createEvent(
                        targetEventId,
                        EVENT_START_DATE
                );

        EventCategory eventCategory =
                createCategory(
                        new BigDecimal("70000")
                );

        BigDecimal result =
                registrationPricingService.calculateContractAmount(
                        event,
                        eventCategory,
                        "1995-05-10"
                );

        assertThat(result)
                .isEqualByComparingTo("70000");
    }


    /**
     * 어린이라도 대상 MarvelRun Event가 아니면 임시 고정가격을 적용하지 않고
     * 해당 EventCategory의 기본 참가비를 사용하는지 검증한다.
     */
    @Test
    void otherEventChildUsesBasePrice() {

        Event event =
                createEvent(
                        "other-event",
                        EVENT_START_DATE
                );

        EventCategory eventCategory =
                createCategory(
                        new BigDecimal("55000")
                );

        BigDecimal result =
                registrationPricingService.calculateContractAmount(
                        event,
                        eventCategory,
                        "2020-01-01"
                );

        assertThat(result)
                .isEqualByComparingTo("55000");
    }


    /**
     * Pricing 테스트에 필요한 최소 Event Mock을 생성한다.
     */
    private Event createEvent(
            String eventId,
            LocalDateTime startDate
    ) {

        Event event =
                mock(Event.class);

        when(event.getId())
                .thenReturn(eventId);

        when(event.getStartDate())
                .thenReturn(startDate);

        return event;
    }


    /**
     * Pricing 테스트에 필요한 최소 EventCategory Mock을 생성한다.
     */
    private EventCategory createCategory(
            BigDecimal amount
    ) {

        EventCategory eventCategory =
                mock(EventCategory.class);

        when(eventCategory.getAmount())
                .thenReturn(amount);

        return eventCategory;
    }
}