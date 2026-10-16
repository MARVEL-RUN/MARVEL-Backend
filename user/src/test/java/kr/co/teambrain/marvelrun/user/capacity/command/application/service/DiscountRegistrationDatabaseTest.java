package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgAccountRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgProfileRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationParticipantRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.OrgRegistrationCreateResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.RegistrationCreateResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import kr.co.teambrain.marvelrun.user.discount.domain.RegistrationPriceCalculation;
import kr.co.teambrain.marvelrun.user.discount.repository.RegistrationPriceCalculationRepository;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentAllocationCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentAllocationTarget;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.stream.IntStream;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** 실제 생성·주문·정원 트랜잭션에 링크 자격과 가격 결과를 연결하여 검증한다. */
class DiscountRegistrationDatabaseTest extends CapacityMvpTestSupport {
    @Autowired
    private RegistrationPriceCalculationRepository calculations;

    @Autowired
    private RegistrationCommandRepository registrationRows;
    @Autowired
    private PaymentCreator paymentCreator;
    @Autowired
    private PaymentAllocationCreator allocationCreator;

    /** 중단 링크로 개인·단체를 생성하면 신청·주문·정원 변경이 남지 않는다. */
    @Test
    void killSwitchBlocksPersonalAndGroupCreation() {
        String code = insertDiscountLink(false, "50");
        jdbc.update("update discount_link set enabled=0 where code=?", code);
        expectError(ErrorCode.REGISTRATION_DISCOUNT_LINK_UNAVAILABLE,
            () -> registrations.register(eventId, request(code)));
        expectError(ErrorCode.REGISTRATION_DISCOUNT_LINK_UNAVAILABLE,
            () -> organizations.register(eventId, groupRequest(code, 2)));
        noApplications();
        assertThat(queryIntegerValue("select count(*) from organization where event_id=?", eventId)).isZero();
        assertCapacityCounts(total, 0, 0);
    }

    /** 주문이 먼저 저장되어도 귀속 단계에서 중단 링크를 감지하면 같은 트랜잭션이 롤백된다. */
    @Test
    void killSwitchRollsBackNewPaymentForExistingPendingRegistration() {
        String code = insertDiscountLink(false, "50");
        RegistrationCreateResponse created = registrations.register(eventId, request(code));
        jdbc.update("update discount_link set enabled=0 where code=?", code);
        int before = queryIntegerValue("select count(*) from payment where registration_id=?", created.registrationId());
        String calculation = queryStringValue("select current_price_calculation_id from registration where id=?", created.registrationId());

        expectError(ErrorCode.DISCOUNT_LINK_PAYMENT_DISABLED, () -> tx.executeWithoutResult(status -> {
            Registration pending = registrationRows.findById(created.registrationId()).orElseThrow();
            Payment payment = paymentCreator.createInitialPayment(pending, UUID.randomUUID().toString());
            allocationCreator.create(payment, List.of(new PaymentAllocationTarget(pending, pending.getContractAmount())));
        }));

        assertThat(queryIntegerValue("select count(*) from payment where registration_id=?", created.registrationId())).isEqualTo(before);
        assertThat(queryStringValue("select current_price_calculation_id from registration where id=?", created.registrationId())).isEqualTo(calculation);
        assertThat(queryStringValue("select discount_link_id from registration where id=?", created.registrationId())).isNotNull();
    }

    /** 일반 신청에도 기본가와 빈 적용 목록을 기록하며 미결제 상태부터 현재 참조가 존재한다. */
    @Test
    void ordinaryPendingRegistrationHasEmptyDiscountCalculation() {
        RegistrationCreateResponse result = registrations.register(eventId, request(null));
        assertThat(queryIntegerValue("select count(*) from registration r join registration_price_calculation c on c.id=r.current_price_calculation_id where r.id=? and c.registration_id=r.id and c.final_amount=r.contract_amount and JSON_LENGTH(c.snapshot,'$.appliedDiscounts')=0",
            result.registrationId())).isEqualTo(1);
        assertThat(queryStringValue("select status from registration where id=?",
            result.registrationId())).isEqualTo("PAYMENT_PENDING");
        assertThat(queryStringValue("select discount_link_id from registration where id=?", result.registrationId())).isNull();
    }

    /** 최소 인원 미달은 단체·신청·결제를 남기지 않고 정상 5명은 같은 자격을 취득한다. */
    @Test
    void minimumFiveIsAtomicAtCreation() {
        String code = insertDiscountLink(false, "50");
        jdbc.update("update discount_policy set minimum_group_size=5,application_type='ORGANIZATION' where event_id=?",eventId);
        expectError(ErrorCode.DISCOUNT_GROUP_MINIMUM_NOT_MET, () -> organizations.register(eventId, groupRequest(code,4)));
        noApplications();
        assertThat(queryIntegerValue("select count(*) from organization where event_id=?",eventId)).isZero();
        limit(total,50);
        limit(categoryACapacity,50);
        limit(shirtS,50);
        OrgRegistrationCreateResponse created = organizations.register(eventId,groupRequest(code,5));
        assertThat(created.registrationIds()).hasSize(5);
        assertThat(queryIntegerValue("select count(*) from registration_price_calculation c join registration r on r.id=c.registration_id where r.event_id=?",
            eventId)).isEqualTo(5);
        assertThat(queryIntegerValue("select count(*) from registration where event_id=? and discount_link_id is not null and reference_filter_id is not null",
            eventId)).isEqualTo(5);
    }

    /** 참가자의 동일성 충돌 없이 지정 인원으로 최초 단체 요청을 만든다. */
    private OrgRegistrationCreateRequest groupRequest(String code, int count) {
        return new OrgRegistrationCreateRequest(new OrgAccountRequest("할인단체", "crewtest", "Test1234!"),
            new OrgProfileRequest("주소", "상세", LocalDate.of(1990,1,1), "010-0000-0000", "test@example.com", "대표자", true),
            IntStream.range(0,count).mapToObj(index -> new OrgRegistrationParticipantRequest(categoryA,
            List.of(new SouvenirJson(souvenirId,
            "S")),
            "단체원"+index,
            "010-0000-0000",
            "1990-01-01",
            GenderClass.M,
            null,
            true)).toList(),
            true,false,false,code);
    }
    /** 신규 신청과 주문 귀속에 동일한 할인 가격이 저장된다. */
    @Test
    void creationPersistsQualificationAndDiscountedPayment() {
        String code = insertDiscountLink(false, "50");
        RegistrationCreateResponse result = registrations.register(eventId, request(code));
        BigDecimal base = jdbc.queryForObject("select amount from event_category where id=?", BigDecimal.class, categoryA);
        BigDecimal expected = base.divide(new BigDecimal("2")).setScale(0, RoundingMode.DOWN);
        assertThat(jdbc.queryForObject("select contract_amount from registration where id=?",
            BigDecimal.class,
            result.registrationId()))
            .isEqualByComparingTo(expected);
        assertThat(queryStringValue("select discount_link_id from registration where id=?", result.registrationId())).isNotNull();
        assertThat(queryStringValue("select reference_filter_id from registration where id=?",
            result.registrationId())).isNotNull();
        assertThat(queryIntegerValue("select JSON_EXTRACT(c.snapshot, '$.appliedDiscounts[0].revision') from registration r join registration_price_calculation c on c.id=r.current_price_calculation_id where r.id=?",
            result.registrationId())).isEqualTo(1);
        // JSON을 명시적 record로 다시 읽어 실제 저장·복원 계약도 검증한다.
        RegistrationPriceCalculation calculation = calculations.findById(
            queryStringValue("select current_price_calculation_id from registration where id=?",
            result.registrationId())).orElseThrow();
        assertThat(calculation.getBaseAmount()).isEqualByComparingTo(base);
        assertThat(calculation.getFinalAmount()).isEqualByComparingTo(expected);
        assertThat(calculation.getSnapshot().schemaVersion()).isEqualTo(1);
        assertThat(calculation.getSnapshot().appliedDiscounts()).hasSize(1);
        assertThat(calculation.getSnapshot().appliedDiscounts().getFirst().ratePercent()).isEqualByComparingTo("50");
        assertThat(jdbc.queryForObject("select amount from payment where registration_id=?",
            BigDecimal.class,
            result.registrationId()))
            .isEqualByComparingTo(expected);
    }

    /** 비대상 종목 신청도 링크/필터를 소유하되 가격 적용 개정은 없다. */
    @Test
    void nonTargetStillAcquiresQualification() {
        String code = insertDiscountLink(true, "50");
        RegistrationCreateResponse result = registrations.register(eventId, request(code));
        assertThat(queryStringValue("select discount_link_id from registration where id=?", result.registrationId())).isNotNull();
        assertThat(queryStringValue("select reference_filter_id from registration where id=?",
            result.registrationId())).isNotNull();
        assertThat(queryIntegerValue("select JSON_LENGTH(c.snapshot, '$.appliedDiscounts') from registration r join registration_price_calculation c on c.id=r.current_price_calculation_id where r.id=?",
            result.registrationId())).isZero();
        assertThat(jdbc.queryForObject("select contract_amount from registration where id=?",
            BigDecimal.class,
            result.registrationId()))
            .isEqualByComparingTo(jdbc.queryForObject("select amount from event_category where id=?",
            BigDecimal.class,
            categoryA));
    }

    /** 0원 계산 실패 시 신청·결제·정원 일부가 남지 않는다. */
    @Test
    void zeroPriceRollsBackCreation() {
        String code = insertDiscountLink(false, "50");
        jdbc.update("update event_category set amount=1 where id=?", categoryA);
        expectError(ErrorCode.DISCOUNT_ZERO_AMOUNT_NOT_SUPPORTED, () -> registrations.register(eventId, request(code)));
        noApplications();
        assertCapacityCounts(total, 0, 0);
    }

    /** 링크 없는 기존 입력에 코드만 추가한다. */
    private RegistrationCreateRequest request(String code) {
        return new RegistrationCreateRequest(categoryA, List.of(new SouvenirJson(souvenirId, "S")), "Test1234!",
            "할인참가자", "010-0000-0000", "1990-01-01", GenderClass.M, "테스트 주소", "상세", true,
            "보호자", null, null, true, false, false, null, null, true, code);
    }

    /** 현재 테스트가 만든 대회 안에서만 정책과 링크를 준비한다. */
    private String insertDiscountLink(boolean nonTarget, String rate) {
        String policy = UUID.randomUUID().toString();
        String filter = UUID.randomUUID().toString();
        String code = UUID.randomUUID().toString();
        jdbc.update("insert into discount_policy(id,event_id,name,stage,calculation_method,rate_percent,application_type,category_scope) values(?,?,'할인검증','EXTERNAL','PERCENT',?,'BOTH',?)",
            policy,eventId,new BigDecimal(rate),nonTarget ? "SELECTED" : "ALL");
        if (nonTarget) {
            jdbc.update("insert into discount_policy_category(id,policy_id,event_category_id) values(?,?,?)",
                UUID.randomUUID().toString(),
                policy,
                categoryB);
        }
        jdbc.update("insert into reference_filter(id,event_id,code,display_name) values(?,?,'TEST_LINK','검증 필터')",filter,eventId);
        jdbc.update("insert into discount_link(id,policy_id,code,reference_filter_id,acquisition_start_at,acquisition_end_at) values(?,?,?,?,?,?)",
            UUID.randomUUID().toString(),policy,code,filter,NOW.minusDays(1),NOW.plusDays(1));
        return code;
    }
}
