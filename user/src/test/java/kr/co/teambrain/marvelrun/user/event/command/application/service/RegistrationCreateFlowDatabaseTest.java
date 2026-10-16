package kr.co.teambrain.marvelrun.user.event.command.application.service;
import kr.co.teambrain.marvelrun.user.event.command.application.service.OrganizationLifecycleService;
import kr.co.teambrain.marvelrun.user.discount.service.LinkDiscountEligibilityService;
import kr.co.teambrain.marvelrun.user.discount.service.OrganizationDiscountGuard;
import kr.co.teambrain.marvelrun.user.discount.service.DiscountPolicyResolver;
import kr.co.teambrain.marvelrun.user.discount.service.RegistrationPriceCalculationRecorder;


import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategoryStartGroup;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryStartGroupRepository;
import org.mockito.Mockito;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.CapacityHoldService;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.CapacityRequirementResolver;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationCapacityService;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationHistoryRecorder;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationReleaseService;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgAccountRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgProfileRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationParticipantRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationApplyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationApplyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationPolicyValidator;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyService;
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
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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
        OrganizationLifecycleService.class,
        LinkDiscountEligibilityService.class,
        OrganizationDiscountGuard.class,
        DiscountPolicyResolver.class, RegistrationPriceCalculationRecorder.class,
        ReservationHistoryRecorder.class,
        RegistrationActionPolicyService.class,
        BCryptPasswordEncoder.class,
        CapacityHoldService.class,
        CapacityRequirementResolver.class,
        RegistrationCapacityService.class,
        ReservationReleaseService.class,

        RegistrationStartGroupResolver.class,
        RegistrationCommandService.class,
        OrgRegistrationCommandService.class,
        OrgParticipantPasswordEncoder.class,
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

    /** 같은 종목의 여러 입력을 한 번에 조회하며 직접 후미 선택과 미선택을 구별한다. */
    @Test
    void resolvesStartGroupsInInputOrderUsingOneCategoryQuery() {
        // B라는 코드에 의존하지 않고 실제 최하위인 C를 선택하도록 준비한다.
        EventCategoryStartGroupRepository groupRepository = Mockito.mock(EventCategoryStartGroupRepository.class);
        EventCategoryStartGroup front = EventCategoryStartGroup.builder()
                .id("front").eventCategoryId("course").code("A").sortOrder(10).active(true).build();
        EventCategoryStartGroup rear = EventCategoryStartGroup.builder()
                .id("rear").eventCategoryId("course").code("C").sortOrder(30).active(true).build();
        Mockito.when(groupRepository.findByEventCategoryIdInAndActiveTrueOrderByEventCategoryIdAscSortOrderAsc(
                List.of("course"))).thenReturn(List.of(front, rear));
        RegistrationStartGroupResolver resolver = new RegistrationStartGroupResolver(groupRepository);

        // true 요청의 잘못된 ID는 무시하고 직접 선택한 같은 후미 UUID는 false로 남긴다.
        List<RegistrationStartGroupResolver.Selection> selections = resolver.resolveRegistrationStartGroups(List.of(
                new RegistrationStartGroupResolver.Input("course", "ignored", true),
                new RegistrationStartGroupResolver.Input("course", "rear", false),
                new RegistrationStartGroupResolver.Input("course", "front", false)));

        // 참가자별 입력 순서와 불린을 보존하고 종목 조회를 반복하지 않는다.
        assertThat(selections).containsExactly(
                new RegistrationStartGroupResolver.Selection("rear", true),
                new RegistrationStartGroupResolver.Selection("rear", false),
                new RegistrationStartGroupResolver.Selection("front", false));
        Mockito.verify(groupRepository, Mockito.times(1))
                .findByEventCategoryIdInAndActiveTrueOrderByEventCategoryIdAscSortOrderAsc(List.of("course"));
    }

    /** 활성 그룹을 조회했더라도 다른 종목의 UUID를 해당 참가자에게 사용할 수 없다. */
    @Test
    void rejectsStartGroupFromAnotherCategoryAndMissingDefinition() {
        // 서로 다른 종목의 그룹이 같은 일괄 조회 결과에 포함되는 상황을 구성한다.
        EventCategoryStartGroupRepository groupRepository = Mockito.mock(EventCategoryStartGroupRepository.class);
        EventCategoryStartGroup first = EventCategoryStartGroup.builder()
                .id("first-group").eventCategoryId("first").sortOrder(10).active(true).build();
        EventCategoryStartGroup second = EventCategoryStartGroup.builder()
                .id("second-group").eventCategoryId("second").sortOrder(10).active(true).build();
        Mockito.when(groupRepository.findByEventCategoryIdInAndActiveTrueOrderByEventCategoryIdAscSortOrderAsc(
                List.of("first", "second"))).thenReturn(List.of(first, second));
        RegistrationStartGroupResolver resolver = new RegistrationStartGroupResolver(groupRepository);

        // 타 종목 선택과 활성 정의 자체의 누락을 서로 다른 업무 오류로 구별한다.
        assertThatThrownBy(() -> resolver.resolveRegistrationStartGroups(List.of(
                new RegistrationStartGroupResolver.Input("first", "second-group", false),
                new RegistrationStartGroupResolver.Input("second", null, true))))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_START_GROUP_SELECTION));
        Mockito.when(groupRepository.findByEventCategoryIdInAndActiveTrueOrderByEventCategoryIdAscSortOrderAsc(
                List.of("missing"))).thenReturn(List.of());
        assertThatThrownBy(() -> resolver.resolveRegistrationStartGroups(List.of(
                new RegistrationStartGroupResolver.Input("missing", null, true))))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.START_GROUP_CONFIGURATION_ERROR));
    }

    /** 내부 호출도 false와 빈 ID 조합을 거부하고 빈 일괄 입력은 DB에 전달하지 않는다. */
    @Test
    void validatesStartGroupInputsBeforeQuerying() {
        // 호출자의 DTO 검증 유무와 독립적인 그룹 결정 계약을 확인한다.
        EventCategoryStartGroupRepository groupRepository = Mockito.mock(EventCategoryStartGroupRepository.class);
        RegistrationStartGroupResolver resolver = new RegistrationStartGroupResolver(groupRepository);

        // 불완전한 선택은 오류이고 대상이 없는 목록은 아무 조회도 하지 않는다.
        assertThatThrownBy(() -> resolver.resolveRegistrationStartGroups(List.of(
                new RegistrationStartGroupResolver.Input("course", null, false))))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_START_GROUP_SELECTION));
        assertThat(resolver.resolveRegistrationStartGroups(List.of())).isEmpty();
        Mockito.verifyNoInteractions(groupRepository);
    }

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
                null, null, true)
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
                                GenderClass.M, null, true),
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
                                GenderClass.M, null, true)
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
