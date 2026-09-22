package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationCreateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationApplyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationApplyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationPolicyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.valid.EventPaymentPolicyValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 MarvelRun 테스트 DB 정책과 Event/Category 데이터를 사용하여
 * 9/22 MVP 임시 가격 계산이 기존 신청 정책과 동일한 경계에서 동작하는지 검증한다.
 *
 * Mock Entity를 사용하는 단위 테스트와 달리 실제 DB에서 Event와 EventCategory를
 * 조회하고 기존 RegistrationApplyValidator를 통과한 Context를 가격 계산에 사용한다.
 */
@Tag("policy-db")
@DataJpaTest(properties = {
        "spring.datasource.url=${MARVELRUN_TEST_DB_URL}",
        "spring.datasource.username=${MARVELRUN_TEST_DB_USERNAME}",
        "spring.datasource.password=${MARVELRUN_TEST_DB_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=false",
        "spring.liquibase.enabled=false"
})
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@Import({
        RegistrationPricingService.class,
        RegistrationPolicyLoader.class,
        RegistrationPolicyValidator.class,
        RegistrationApplyValidator.class,
        OrgRegistrationApplyValidator.class,
        EventPaymentPolicyValidator.class
})
class RegistrationPricingServiceDatabaseTest {

    private static final String EVENT_ID =
            "test-marvelrun";

    /*
     * 실제 test-marvelrun 신청 기간 안의 고정 시각을 사용한다.
     * 시스템 실행 날짜와 테스트 결과를 분리한다.
     */
    private static final LocalDateTime APPLICATION_TIME =
            LocalDateTime.of(2026, 9, 20, 12, 0);

    @Autowired
    private EventCommandRepository eventCommandRepository;

    @Autowired
    private RegistrationApplyValidator registrationApplyValidator;

    @Autowired
    private RegistrationPricingService registrationPricingService;

    private Event event;


    /**
     * Pricing DB 테스트가 의존하는 test-marvelrun의 기준일을 먼저 검증한다.
     */
    @BeforeEach
    void loadTestEvent() {

        event = eventCommandRepository.findById(EVENT_ID)
                .orElseThrow(
                        () -> new AssertionError(
                                "test-marvelrun 대회가 없습니다."
                        )
                );

        assertThat(event.getStartDate().toLocalDate())
                .as("Pricing 테스트 기준 대회일")
                .isEqualTo(
                        LocalDate.of(2026, 9, 10)
                );
    }


    /**
     * 실제 DB의 5km 신청 정책을 통과한 어린이 참가자에게
     * Category 기본금액이 아닌 40,000원 고정가격이 적용되는지 검증한다.
     */
    @Test
    void validatedFiveKmChildUsesFixedPrice() {

        RegistrationCreateContext context =
                registrationApplyValidator.validate(
                        EVENT_ID,
                        request(
                                "category-2",
                                "2013-09-11",
                                "130"
                        ),
                        APPLICATION_TIME
                );

        /*
         * 테스트 데이터 자체가 변경되어 테스트 의미가 사라지는 것을 막는다.
         */
        assertThat(context.eventCategory().getAmount())
                .isEqualByComparingTo("70000");

        BigDecimal result =
                registrationPricingService.calculateContractAmount(
                        context.event(),
                        context.eventCategory(),
                        "2013-09-11"
                );

        assertThat(result)
                .isEqualByComparingTo("40000");
    }


    /**
     * 실제 DB의 어린이 경계 직전 출생자는 정책 검증을 통과하더라도
     * 어린이 고정가격 대상이 아니며 5km 기본가격을 사용하는지 검증한다.
     */
    @Test
    void thirteenthBirthdayOnEventDateUsesStoredBasePrice() {

        RegistrationCreateContext context =
                registrationApplyValidator.validate(
                        EVENT_ID,
                        request(
                                "category-2",
                                "2013-09-10",
                                "M"
                        ),
                        APPLICATION_TIME
                );

        assertThat(context.eventCategory().getAmount())
                .isEqualByComparingTo("70000");

        BigDecimal result =
                registrationPricingService.calculateContractAmount(
                        context.event(),
                        context.eventCategory(),
                        "2013-09-10"
                );

        assertThat(result)
                .isEqualByComparingTo("70000");
    }


    /**
     * 기본금액이 55,000원인 2.3km 종목에서도 어린이이면
     * 동일하게 40,000원 고정가격이 적용되는지 검증한다.
     *
     * 이를 통해 특정 Category 가격을 40,000원으로 잘못 바꾸는 방식이 아니라
     * 어린이 가격 정책이 적용되고 있음을 확인한다.
     */
    @Test
    void validatedTwoPointThreeKmChildUsesFixedPrice() {

        RegistrationCreateContext context =
                registrationApplyValidator.validate(
                        EVENT_ID,
                        request(
                                "category-3",
                                "2013-09-11",
                                "150"
                        ),
                        APPLICATION_TIME
                );

        assertThat(context.eventCategory().getAmount())
                .isEqualByComparingTo("55000");

        BigDecimal result =
                registrationPricingService.calculateContractAmount(
                        context.event(),
                        context.eventCategory(),
                        "2013-09-11"
                );

        assertThat(result)
                .isEqualByComparingTo("40000");
    }


    /**
     * 실제 DB의 일반 성인 신청에서는 임시 어린이 가격정책이 적용되지 않고
     * EventCategory에 저장된 참가비를 그대로 사용하는지 검증한다.
     */
    @Test
    void validatedAdultUsesStoredCategoryAmount() {

        RegistrationCreateContext context =
                registrationApplyValidator.validate(
                        EVENT_ID,
                        request(
                                "category-3",
                                "1990-01-01",
                                "M"
                        ),
                        APPLICATION_TIME
                );

        assertThat(context.eventCategory().getAmount())
                .isEqualByComparingTo("55000");

        BigDecimal result =
                registrationPricingService.calculateContractAmount(
                        context.event(),
                        context.eventCategory(),
                        "1990-01-01"
                );

        assertThat(result)
                .isEqualByComparingTo("55000");
    }


    /**
     * 실제 신청 Validator와 Pricing 검증에 사용할 최소 개인 신청 Request를 생성한다.
     *
     * 어린이 케이스는 기존 정책에 따라 보호자명과 보호자 동의를 함께 제공한다.
     */
    private RegistrationCreateRequest request(
            String categoryId,
            String birth,
            String shirtSize
    ) {

        return new RegistrationCreateRequest(categoryId,
                List.of(
                        new SouvenirJson(
                                "souvenir-tshirt",
                                shirtSize
                        )
                ),
                "TestPassword1!",
                "가격테스트-"
                        + UUID.randomUUID()
                        .toString()
                        .substring(0, 8),
                "010-0000-0000",
                birth,
                GenderClass.M,
                "테스트 주소",
                "테스트 상세주소",
                true,
                "테스트 보호자",
                null,
                null,
                true,
                false,
                false,
                null);
    }
}