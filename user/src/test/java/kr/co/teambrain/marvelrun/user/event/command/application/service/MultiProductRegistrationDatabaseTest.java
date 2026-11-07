package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityRequirementDiff;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.CapacityHoldService;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.CapacityRequirementResolver;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationCapacityService;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationCapacityDiffService;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationHistoryRecorder;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationReleaseService;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.discount.service.DiscountPolicyResolver;
import kr.co.teambrain.marvelrun.user.discount.service.LinkDiscountEligibilityService;
import kr.co.teambrain.marvelrun.user.discount.service.OrganizationDiscountGuard;
import kr.co.teambrain.marvelrun.user.discount.service.RegistrationPriceCalculationRecorder;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationItem;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationEntryRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationItemRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationApplyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationApplyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationEntryValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationPolicyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.loader.RegistrationPolicyLoader;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 버추얼런 대회(virtual-marvelrun)의 실제 상품·정원 설정으로 상품 여러 개를 담은 신청 생성을 DB 기준으로 확인한다.
 *
 * 구매 줄·참가 항목 매핑, 줄 가격 합 = 계약금액 = 결제금액, 정원 행별 수량 합산(예약 상세 한 줄),
 * 전체 정원은 사람 수만큼, 같은 신청자 주문 여러 건 허용 대회의 유니크 인덱스 동작을 본다.
 * DataJpaTest 트랜잭션이 끝나면 모두 롤백된다.
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
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
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
        RegistrationEntryValidator.class,
        RegistrationPolicyLoader.class,
        EventPaymentPolicyValidator.class,
        PaymentCreator.class,
        PaymentAllocationCreator.class,
        PaymentOrderIdGenerator.class,
        PackageUpgradeCapacitySupport.class,
        ReservationCapacityDiffService.class
})
class MultiProductRegistrationDatabaseTest {

    private static final String EVENT_ID = "virtual-marvelrun";
    /** 1차수 접수 기간 안의 고정 시각 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 12, 0);
    /** 단품 종목마다 연결된 부문 키트. 패키지는 세 키트 모두에 연결돼 있다(로컬 DB #136 데이터). */
    private static final Map<String, String> KIT_BY_SINGLE = Map.of(
            "vr-avengers", "vr-kit-avengers",
            "vr-xmen", "vr-kit-xmen",
            "vr-fantastic4", "vr-kit-fantastic4");
    private static final List<SouvenirJson> PACKAGE_KITS = List.of(
            new SouvenirJson("vr-kit-avengers", "FREE"),
            new SouvenirJson("vr-kit-xmen", "FREE"),
            new SouvenirJson("vr-kit-fantastic4", "FREE"));

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RegistrationCommandService registrations;
    @Autowired private TestEntityManager entityManager;
    @Autowired private PackageUpgradeCapacitySupport upgradeSupport;
    @Autowired private ReservationCommandRepository reservationRepository;
    @MockitoBean private ServerTimeProvider time;

    @BeforeEach
    void setCurrentTime() {
        when(time.currentDateTime()).thenReturn(NOW);
    }

    /**
     * 어벤저스 단품 2개 + 3종 패키지 1개를 한 신청으로 산다.
     * 줄 3개·항목 5개가 줄에 묶여 저장되고, 계약금액·결제금액은 줄 가격 합 155,000원이다.
     * 정원은 같은 행끼리 합쳐 예약 상세 한 줄로 들어가고 전체 정원은 사람 수 5만큼 늘어난다.
     */
    @Test
    void twoSinglesAndOnePackageCreateItemsEntriesAndSummedCapacity() {
        Map<String, Integer> heldBefore = heldCounts();
        String name = uniqueName("복수구매");

        var result = registrations.register(EVENT_ID, request(name, List.of(
                single("vr-avengers", "아이1"),
                single("vr-avengers", "아이2"),
                pack())));
        entityManager.flush();

        assertThat(jdbc.queryForList("""
                select event_category_id, sort_order, amount
                from registration_item where registration_id = ? order by sort_order
                """, result.registrationId()))
                .extracting(row -> row.get("event_category_id") + ":" + row.get("sort_order") + ":"
                        + ((BigDecimal) row.get("amount")).stripTrailingZeros().toPlainString())
                .containsExactly("vr-avengers:1:40000", "vr-avengers:2:40000", "vr-package:3:75000");
        assertThat(jdbc.queryForList("""
                select i.sort_order, e.event_category_id, e.name
                from registration_entry e join registration_item i on i.id = e.registration_item_id
                where e.registration_id = ? order by i.sort_order, e.event_category_id
                """, result.registrationId()))
                .extracting(row -> row.get("sort_order") + ":" + row.get("event_category_id") + ":" + row.get("name"))
                .containsExactly("1:vr-avengers:아이1", "2:vr-avengers:아이2",
                        "3:vr-avengers:엄마", "3:vr-fantastic4:둘째", "3:vr-xmen:아빠");

        Map<String, Object> registration = jdbc.queryForMap("""
                select event_category_id, contract_amount, duplicate_allowed, active_unique_info
                from registration where id = ?
                """, result.registrationId());
        assertThat(registration.get("event_category_id")).isEqualTo("vr-avengers");
        assertThat((BigDecimal) registration.get("contract_amount")).isEqualByComparingTo("155000");
        assertThat(jdbc.queryForObject("select amount from payment where id = ?", BigDecimal.class, result.paymentId()))
                .isEqualByComparingTo("155000");
        assertThat(jdbc.queryForObject("select order_name from payment where id = ?", String.class, result.paymentId()))
                .endsWith(" - 5km | 어벤저스 외 2건");

        Map<String, Integer> reserved = new HashMap<>();
        jdbc.query("""
                select ri.capacity_id, ri.quantity
                from reservation_item ri join reservation r on r.id = ri.reservation_id
                where r.registration_id = ?
                """, rs -> { reserved.put(rs.getString(1), rs.getInt(2)); }, result.registrationId());
        // 키트는 부문마다 센다. 어벤저스 단품 2개 + 패키지 1개 = 어벤저스 키트 3, 패키지의 엑스맨·판타스틱4 키트 1씩.
        assertThat(reserved).isEqualTo(Map.of(
                "vr-cap-total", 5,
                "vr-cap-cat-avengers", 2,
                "vr-cap-cat-package", 1,
                "vr-cap-grp-avengers", 3,
                "vr-cap-grp-xmen", 1,
                "vr-cap-grp-fantastic4", 1,
                "vr-cap-kit-avengers", 3,
                "vr-cap-kit-xmen", 1,
                "vr-cap-kit-fantastic4", 1));
        Map<String, Integer> heldAfter = heldCounts();
        reserved.forEach((capacityId, quantity) ->
                assertThat(heldAfter.get(capacityId) - heldBefore.get(capacityId)).isEqualTo(quantity));

        // 줄 기념품: 단품은 자기 부문 키트 하나, 패키지는 키트 셋. 신청 행은 대표(첫) 줄 값이다.
        assertThat(jdbc.queryForList("""
                select souvenir_json from registration_item where registration_id = ? order by sort_order
                """, String.class, result.registrationId()))
                .satisfiesExactly(
                        json -> assertThat(json).contains("vr-kit-avengers").doesNotContain("vr-kit-xmen"),
                        json -> assertThat(json).contains("vr-kit-avengers").doesNotContain("vr-kit-xmen"),
                        json -> assertThat(json).contains("vr-kit-avengers", "vr-kit-xmen", "vr-kit-fantastic4"));
        assertThat(jdbc.queryForObject("select souvenir_json from registration where id = ?", String.class,
                result.registrationId())).contains("vr-kit-avengers").doesNotContain("vr-kit-xmen");
    }

    /** 패키지 줄은 연결된 부문 키트 셋을 모두 보내야 한다. 하나만 보내면 기념품 구성 오류다. */
    @Test
    void packageWithOneKitIsRejected() {
        assertThatThrownBy(() -> registrations.register(EVENT_ID, request(uniqueName("키트하나"),
                List.of(pack(List.of(new SouvenirJson("vr-kit-avengers", "FREE")))))))
                .isInstanceOfSatisfying(CustomException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_EVENT_CATEGORY_SOUVENIR));
    }

    /** 부문 키트 하나라도 재고가 없으면 그 키트가 든 상품은 정원 확보에 실패한다. 다른 부문 단품은 살 수 있다. */
    @Test
    void soldOutDivisionKitBlocksProductsWithThatKit() {
        jdbc.update("update capacity set limit_count = held_count + confirmed_count where id = 'vr-cap-kit-xmen'");

        assertThatThrownBy(() -> registrations.register(EVENT_ID, request(uniqueName("키트품절"), List.of(pack()))))
                .isInstanceOfSatisfying(CustomException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.CAPACITY_ACQUIRE_FAILED));
        assertThatThrownBy(() -> registrations.register(EVENT_ID, request(uniqueName("키트품절"),
                List.of(single("vr-xmen", "아이")))))
                .isInstanceOfSatisfying(CustomException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.CAPACITY_ACQUIRE_FAILED));
        var avengers = registrations.register(EVENT_ID, request(uniqueName("키트품절"),
                List.of(single("vr-avengers", "아이"))));
        assertThat(avengers.registrationId()).isNotBlank();
    }

    /**
     * 어벤저스 단품을 패키지로 상향할 때의 정원 차이는 패키지 키트 셋 기준이다.
     * 엑스맨·판타스틱4 키트가 1씩 늘고, 어벤저스 키트는 그대로다.
     */
    @Test
    void upgradeDiffAddsOtherDivisionKits() {
        var result = registrations.register(EVENT_ID, request(uniqueName("상향차이"), List.of(single("vr-avengers", "아이"))));
        entityManager.flush();
        entityManager.clear();
        RegistrationItem item = entityManager.getEntityManager().createQuery(
                        "select i from RegistrationItem i join fetch i.registration r join fetch r.event where r.id = :id",
                        RegistrationItem.class)
                .setParameter("id", result.registrationId()).getSingleResult();
        Reservation reservation = reservationRepository.findByRegistration_Id(result.registrationId()).orElseThrow();
        EventCategory packageCategory = entityManager.find(EventCategory.class, "vr-package");

        assertThat(upgradeSupport.packageSouvenirs(item, packageCategory)).isEqualTo(PACKAGE_KITS);
        CapacityRequirementDiff diff = upgradeSupport.packageDiff(item, packageCategory, 3, reservation);

        Map<String, Integer> increases = new HashMap<>();
        Map<String, Integer> decreases = new HashMap<>();
        for (CapacityRequirementDiff.Item changed : diff.items()) {
            if (changed.increase() > 0) { increases.put(changed.capacityId(), changed.increase()); }
            if (changed.decrease() > 0) { decreases.put(changed.capacityId(), changed.decrease()); }
        }
        assertThat(increases).isEqualTo(Map.of(
                "vr-cap-total", 2,
                "vr-cap-cat-package", 1,
                "vr-cap-grp-xmen", 1,
                "vr-cap-grp-fantastic4", 1,
                "vr-cap-kit-xmen", 1,
                "vr-cap-kit-fantastic4", 1));
        assertThat(decreases).isEqualTo(Map.of("vr-cap-cat-avengers", 1));
    }

    /**
     * 같은 신청자 주문 여러 건을 허용한 대회도 공개 신청의 두 번째 주문은 새 오류로 거절한다.
     * 로그인한 추가 주문으로 만든 두 번째 신청은 유니크 인덱스에 걸리지 않는다(생성 컬럼이 NULL).
     */
    @Test
    void duplicateAllowedEventRejectsPublicSecondOrderButIndexAllowsAdditionalOrder() {
        jdbc.update("update event_registration_policy set duplicate_registration_allowed = 1 where event_id = ?", EVENT_ID);
        String name = uniqueName("추가주문");
        var first = registrations.register(EVENT_ID, request(name, List.of(single("vr-avengers", "아이1"))));
        entityManager.flush();

        assertThatThrownBy(() -> registrations.register(EVENT_ID, request(name, List.of(single("vr-xmen", "아이2")))))
                .isInstanceOfSatisfying(CustomException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.REGISTRATION_ADDITIONAL_ORDER_LOGIN_REQUIRED));

        Map<String, Object> source = jdbc.queryForMap(
                "select password, orderer_key from registration where id = ?", first.registrationId());
        var second = registrations.registerAdditional(EVENT_ID, request(name, List.of(single("vr-xmen", "아이2"))),
                (String) source.get("password"), (String) source.get("orderer_key"));
        entityManager.flush();

        assertThat(jdbc.queryForList("""
                select duplicate_allowed, active_unique_info, password, orderer_key from registration where id in (?, ?)
                """, first.registrationId(), second.registrationId()))
                .allSatisfy(row -> {
                    assertThat(row.get("duplicate_allowed")).isIn(true, 1, (byte) 1);
                    assertThat(row.get("active_unique_info")).isNull();
                    // 추가 주문은 첫 주문의 비밀번호 해시와 주문자 키를 그대로 가진다.
                    assertThat(row.get("password")).isEqualTo(source.get("password"));
                    assertThat(row.get("orderer_key")).isEqualTo(source.get("orderer_key"));
                });
        assertThat((String) source.get("orderer_key")).isNotBlank().isNotEqualTo(first.registrationId());
    }

    /** 같은 신청자 주문 여러 건을 허용하지 않은 대회는 지금처럼 이미 신청한 대회로 거절한다. */
    @Test
    void duplicateNotAllowedEventKeepsRejectingSecondOrder() {
        // 로컬 DB에 허용 값이 켜져 있어도 이 테스트는 허용하지 않는 대회로 본다(트랜잭션 롤백).
        jdbc.update("update event_registration_policy set duplicate_registration_allowed = 0 where event_id = ?", EVENT_ID);
        String name = uniqueName("중복불가");
        var first = registrations.register(EVENT_ID, request(name, List.of(single("vr-avengers", "아이1"))));
        entityManager.flush();
        Map<String, Object> source = jdbc.queryForMap(
                "select password, orderer_key from registration where id = ?", first.registrationId());

        assertThatThrownBy(() -> registrations.registerAdditional(EVENT_ID, request(name, List.of(single("vr-xmen", "아이2"))),
                (String) source.get("password"), (String) source.get("orderer_key")))
                .isInstanceOfSatisfying(CustomException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.REGISTRATION_ALREADY_EXISTS));
    }

    private Map<String, Integer> heldCounts() {
        Map<String, Integer> result = new HashMap<>();
        jdbc.query("select id, held_count from capacity where event_id = ?",
                rs -> { result.put(rs.getString(1), rs.getInt(2)); }, EVENT_ID);
        return result;
    }

    private static RegistrationItemRequest single(String categoryId, String recipient) {
        return new RegistrationItemRequest(categoryId, List.of(new SouvenirJson(KIT_BY_SINGLE.get(categoryId), "FREE")),
                List.of(new RegistrationEntryRequest(categoryId, recipient, "010-1111-2222", "1995-03-04", GenderClass.F)));
    }

    private static RegistrationItemRequest pack() {
        return pack(PACKAGE_KITS);
    }

    private static RegistrationItemRequest pack(List<SouvenirJson> kits) {
        return new RegistrationItemRequest("vr-package", kits, List.of(
                new RegistrationEntryRequest("vr-avengers", "엄마", "010-1111-2222", "1980-03-02", GenderClass.F),
                new RegistrationEntryRequest("vr-xmen", "아빠", "010-3333-4444", "1979-07-07", GenderClass.M),
                new RegistrationEntryRequest("vr-fantastic4", "둘째", "010-5555-6666", "1999-09-09", GenderClass.F)));
    }

    private static RegistrationCreateRequest request(String name, List<RegistrationItemRequest> items) {
        return new RegistrationCreateRequest(null, null, "Test1234!", name, "010-9999-0000", "1980-03-02",
                GenderClass.F, "테스트 주소", "상세", false, null, null, null, true, false, false, null,
                null, true, null, null, items);
    }

    private static String uniqueName(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }
}
