package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.CapacityHoldService;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.CapacityRequirementResolver;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationCapacityService;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationReleaseService;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgAccountRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgProfileRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationParticipantRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationApplyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationApplyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationPolicyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentAllocationCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.generator.PaymentOrderIdGenerator;
import kr.co.teambrain.marvelrun.user.payment.command.application.valid.EventPaymentPolicyValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

/**
 * 실제 test-marvelrun 정책·가격·Capacity 데이터를 사용하여
 * 04 개인/단체 신청 생성 전체 흐름을 DB 기준으로 검증한다.
 *
 * DataJpaTest 기본 Transaction을 유지하여 테스트에서 생성한
 * Registration, Reservation, Payment, Allocation 및 Capacity 변경은
 * 테스트 종료 시 모두 롤백한다.
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
        CapacityHoldService.class,
        CapacityRequirementResolver.class,
        RegistrationCapacityService.class,
        ReservationReleaseService.class,

        RegistrationCommandService.class,
        OrgRegistrationCommandService.class,
        RegistrationPricingService.class,

        RegistrationApplyValidator.class,
        OrgRegistrationApplyValidator.class,
        RegistrationPolicyValidator.class,
        RegistrationPolicyLoader.class,
        EventPaymentPolicyValidator.class,

        PaymentCreator.class,
        PaymentAllocationCreator.class,
        PaymentOrderIdGenerator.class
})
class RegistrationCreateFlowDatabaseTest {

    private static final String EVENT_ID =
            "test-marvelrun";

    private static final LocalDateTime APPLICATION_TIME =
            LocalDateTime.of(
                    2026,
                    9,
                    20,
                    12,
                    0
            );

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RegistrationCommandService registrations;

    @Autowired
    private OrgRegistrationCommandService organizations;

    @Autowired
    private TestEntityManager entityManager;

    @MockitoBean
    private ServerTimeProvider time;


    /**
     * 실제 테스트 대회의 신청기간 안에 있는 고정 시각을 사용한다.
     */
    @BeforeEach
    void setCurrentTime() {
        when(
                time.currentDateTime()
        ).thenReturn(
                APPLICATION_TIME
        );
    }


    /**
     * 실제 5km 어린이 신청이 Validation, Pricing, Capacity,
     * Reservation, Payment, Allocation까지 한 흐름으로 생성되는지 검증한다.
     */
    @Test
    void childPersonalApplicationCreatesFortyThousandWonAllocation() {

        var result =
                registrations.register(
                        EVENT_ID,
                        new RegistrationCreateRequest("category-2",
                List.of(
                                        new SouvenirJson(
                                                "souvenir-tshirt",
                                                "130"
                                        )
                                ),
                "Test1234!",
                uniqueName(
                                        "개인어린이"
                                ),
                "010-0000-0000",
                "2013-09-11",
                GenderClass.M,
                "테스트 주소",
                "상세",
                true,
                "테스트 보호자",
                null,
                null,
                true,
                false,
                false,
                null)
                );

        /*
         * 신청 Transaction에서 persist된 Payment / PaymentAllocation을
         * JdbcTemplate으로 조회하기 전에 실제 DB에 flush한다.
         *
         * 테스트 Transaction은 commit하지 않으며,
         * 테스트 종료 시 DataJpaTest가 전체 변경을 rollback한다.
         */
        entityManager.flush();

        BigDecimal contractAmount =
                money(
                        """
                        select contract_amount
                        from registration
                        where id = ?
                        """,
                        result.registrationId()
                );

        BigDecimal paymentAmount =
                money(
                        """
                        select amount
                        from payment
                        where id = ?
                        """,
                        result.paymentId()
                );

        assertThat(contractAmount)
                .isEqualByComparingTo("40000");

        assertThat(paymentAmount)
                .isEqualByComparingTo("40000");

        assertThat(
                count(
                        """
                        select count(*)
                        from payment_allocation
                        where payment_id = ?
                        """,
                        result.paymentId()
                )
        ).isEqualTo(1);

        assertThat(
                money(
                        """
                        select allocated_amount
                        from payment_allocation
                        where payment_id = ?
                          and registration_id = ?
                        """,
                        result.paymentId(),
                        result.registrationId()
                )
        ).isEqualByComparingTo("40000");

        assertThat(
                count(
                        """
                        select count(*)
                        from reservation
                        where registration_id = ?
                          and status = 'HELD'
                        """,
                        result.registrationId()
                )
        ).isEqualTo(1);

        /*
         * 어린이 5km 신청은
         * EVENT_TOTAL + CATEGORY + CHILD_CATEGORY + SOUVENIR
         * 네 종류의 실제 Capacity를 점유한다.
         */
        assertThat(
                count(
                        """
                        select count(*)
                        from reservation_item ri
                        join reservation r
                          on r.id = ri.reservation_id
                        where r.registration_id = ?
                        """,
                        result.registrationId()
                )
        ).isEqualTo(4);
    }


    /**
     * 실제 단체 신청에서 성인과 어린이가 서로 다른 가격을 가지더라도
     * 단체 Payment 총액과 Registration별 Allocation이 정확히 구성되는지 검증한다.
     */
    @Test
    void mixedGroupApplicationCreatesAllocationFromEachContractAmount() {

        var result =
                organizations.register(
                        EVENT_ID,
                        groupRequest()
                );

        /*
         * 신청 Transaction에서 persist된 Payment / PaymentAllocation을
         * JdbcTemplate으로 조회하기 전에 실제 DB에 flush한다.
         *
         * 테스트 Transaction은 commit하지 않으며,
         * 테스트 종료 시 DataJpaTest가 전체 변경을 rollback한다.
         */
        entityManager.flush();

        assertThat(result.registrationIds())
                .hasSize(2);

        String adultRegistrationId =
                result.registrationIds()
                        .get(0);

        String childRegistrationId =
                result.registrationIds()
                        .get(1);

        assertThat(
                money(
                        """
                        select contract_amount
                        from registration
                        where id = ?
                        """,
                        adultRegistrationId
                )
        ).isEqualByComparingTo("70000");

        assertThat(
                money(
                        """
                        select contract_amount
                        from registration
                        where id = ?
                        """,
                        childRegistrationId
                )
        ).isEqualByComparingTo("40000");

        assertThat(
                money(
                        """
                        select amount
                        from payment
                        where id = ?
                        """,
                        result.paymentId()
                )
        ).isEqualByComparingTo("110000");

        assertThat(
                count(
                        """
                        select count(*)
                        from payment_allocation
                        where payment_id = ?
                        """,
                        result.paymentId()
                )
        ).isEqualTo(2);

        assertThat(
                money(
                        """
                        select allocated_amount
                        from payment_allocation
                        where payment_id = ?
                          and registration_id = ?
                        """,
                        result.paymentId(),
                        adultRegistrationId
                )
        ).isEqualByComparingTo("70000");

        assertThat(
                money(
                        """
                        select allocated_amount
                        from payment_allocation
                        where payment_id = ?
                          and registration_id = ?
                        """,
                        result.paymentId(),
                        childRegistrationId
                )
        ).isEqualByComparingTo("40000");

        assertThat(
                money(
                        """
                        select coalesce(sum(allocated_amount), 0)
                        from payment_allocation
                        where payment_id = ?
                        """,
                        result.paymentId()
                )
        ).isEqualByComparingTo("110000");
    }


    /**
     * 실제 test-marvelrun의 성인/어린이 혼합 단체 신청 Request를 생성한다.
     *
     * 첫 번째 참가자는 5km 성인 70,000원,
     * 두 번째 참가자는 2.3km 어린이 40,000원이다.
     */
    private OrgRegistrationCreateRequest groupRequest() {

        return new OrgRegistrationCreateRequest(
                new OrgAccountRequest(
                        "통합테스트단체",
                        "g"
                                + UUID.randomUUID()
                                .toString()
                                .replace("-", "")
                                .substring(0, 15),
                        "Test1234!"
                ),
                new OrgProfileRequest(
                        "테스트 주소",
                        "상세",
                        LocalDate.of(
                                1990,
                                1,
                                1
                        ),
                        "010-0000-0000",
                        "integration@example.com",
                        "테스트 단체장",
                        true
                ),
                List.of(
                        new OrgRegistrationParticipantRequest(
                                "category-2",
                                List.of(
                                        new SouvenirJson(
                                                "souvenir-tshirt",
                                                "M"
                                        )
                                ),
                                uniqueName(
                                        "단체성인"
                                ),
                                "010-0000-0000",
                                "1990-01-01",
                                GenderClass.M
                        ),
                        new OrgRegistrationParticipantRequest(
                                "category-3",
                                List.of(
                                        new SouvenirJson(
                                                "souvenir-tshirt",
                                                "150"
                                        )
                                ),
                                uniqueName(
                                        "단체어린이"
                                ),
                                "010-0000-0000",
                                "2013-09-11",
                                GenderClass.M
                        )
                ),
                        true, false, false
                );
    }


    /**
     * 테스트 간 신청자 중복검증 충돌을 피하기 위한 이름을 생성한다.
     *
     * @param prefix 식별용 이름 접두사
     * @return 테스트용 고유 이름
     */
    private String uniqueName(
            String prefix
    ) {
        return prefix
                + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 8);
    }


    /**
     * 금액 컬럼 하나를 BigDecimal로 조회한다.
     *
     * @param sql 실행 SQL
     * @param args 바인딩 인자
     * @return 조회 금액
     */
    private BigDecimal money(
            String sql,
            Object... args
    ) {
        return jdbc.queryForObject(
                sql,
                BigDecimal.class,
                args
        );
    }


    /**
     * COUNT 결과 하나를 조회한다.
     *
     * @param sql 실행 SQL
     * @param args 바인딩 인자
     * @return 조회 건수
     */
    private int count(
            String sql,
            Object... args
    ) {
        return jdbc.queryForObject(
                sql,
                Integer.class,
                args
        );
    }
}