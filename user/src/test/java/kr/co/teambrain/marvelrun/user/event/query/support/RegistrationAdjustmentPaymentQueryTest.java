package kr.co.teambrain.marvelrun.user.event.query.support;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventStatus;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyModels.*;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyService;
import kr.co.teambrain.marvelrun.user.event.query.dto.OrgRegistrationQueryResponse;
import kr.co.teambrain.marvelrun.user.event.query.repository.RegistrationQueryRepository;
import kr.co.teambrain.marvelrun.user.event.query.service.RegistrationQueryService;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import kr.co.teambrain.marvelrun.user.event.query.repository.RegistrationQueryData.*;
import kr.co.teambrain.marvelrun.user.event.query.dto.RegistrationPaymentAction;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
/* 조회 시 일반 추가 결제 안내와 외부 결제 신청의 온라인 결제 제한을 검증한다. */
class RegistrationAdjustmentPaymentQueryTest {
    private final RegistrationPaymentQueryResolver resolver = new RegistrationPaymentQueryResolver();
    private final LocalDateTime now = LocalDateTime.of(2026,11,2,12,0);
    @Test void additionalPaymentClosesAfterDeadline() {
        var result = resolver.resolveRegistrationPaymentGuidance(List.of(member(true)),List.of(),List.of(),List.of(),now.minusDays(1),now);
        assertThat(result.action()).isEqualTo(RegistrationPaymentAction.PAYMENT_CLOSED);
        assertThat(result.paymentId()).isNull(); assertThat(result.orderId()).isNull();
    }
    @Test void initialPaymentStillClosesAtDeadline() {
        assertThat(resolver.resolveRegistrationPaymentGuidance(List.of(member(false)),List.of(),List.of(),List.of(),now,now).action())
                .isEqualTo(RegistrationPaymentAction.PAYMENT_CLOSED);
    }
    @Test void matchingAdditionalReadyOrderUsesExistingPreparation() {
        Payment payment = new Payment("p","r","o",new BigDecimal("30000"),PaymentPurpose.ADDITIONAL_PAYMENT,PaymentProcessStatus.READY);
        var result = resolver.resolveRegistrationPaymentGuidance(List.of(member(true)),List.of(payment),
                List.of(new Allocation("p","r",new BigDecimal("30000"),PaymentPurpose.ADDITIONAL_PAYMENT)),List.of(),now.plusDays(1),now);
        assertThat(result.action()).isEqualTo(RegistrationPaymentAction.PREPARE_PAYMENT);
        assertThat(result.paymentId()).isEqualTo("p");
    }
    @Test void unknownApprovalWinsOverAdditionalButton() {
        Payment payment = new Payment("p","r","o",new BigDecimal("30000"),PaymentPurpose.ADDITIONAL_PAYMENT,PaymentProcessStatus.UNKNOWN);
        assertThat(resolver.resolveRegistrationPaymentGuidance(List.of(member(true)),List.of(payment),List.of(),List.of(),now.minusDays(1),now).action())
                .isEqualTo(RegistrationPaymentAction.WAIT);
    }
    /* 외부 신청에 미납 READY 주문이 남아 있더라도 온라인 결제 버튼을 안내하지 않는다. */
    @Test
    void externalPaymentNeverOffersOnlinePaymentPreparation() {
        // 미납 상태까지 구성하여 납부 완료 여부에 의존하지 않는 제한을 확인한다.
        Member external = new Member("r", "외부결제자", null, "1990-01-01", "010-0000-0000", "test",
                null, "c", "category", List.of(), null, null, null, false, null, null, null,
                RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED, new BigDecimal("70000"),
                new BigDecimal("40000"), false, ReservationStatus.CONSUMED, now.plusDays(1), true, now.minusDays(2), null);
        Payment payment = new Payment("p", "r", "o", new BigDecimal("30000"),
                PaymentPurpose.ADDITIONAL_PAYMENT, PaymentProcessStatus.READY);

        // 외부 결제는 현재 금융 상태를 반환하되 실행 가능한 주문 ID를 제공하지 않는다.
        RegistrationPaymentQueryResolver.Result result = resolver.resolveRegistrationPaymentGuidance(List.of(external), List.of(payment),
                List.of(new Allocation("p", "r", new BigDecimal("30000"), PaymentPurpose.ADDITIONAL_PAYMENT)),
                List.of(), now.plusDays(1), now);
        assertThat(result.action()).isEqualTo(RegistrationPaymentAction.NONE);
        assertThat(result.paymentId()).isNull();
        assertThat(result.status()).isEqualTo(PaymentProcessStatus.READY);
    }

    /** 실제 상세 조립 결과에 단체·구성원별 키와 결제 정책을 제공한다. */
    @Test
    void organizationLookupSeparatesMemberPolicyAndIgnoresFullyPaidPaymentRestriction() {
        RegistrationQueryRepository repository = mock(RegistrationQueryRepository.class);
        RegistrationActionPolicyService policies = mock(RegistrationActionPolicyService.class);
        ServerTimeProvider time = mock(ServerTimeProvider.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        EventInput event = new EventInput("e", EventStatus.OPEN, now.minusMonths(1), null, null);
        Member paid = policyMember("paid", now.minusDays(3), new BigDecimal("70000"),
                new BigDecimal("70000"), RegistrationStatus.CONFIRMED, event);
        Member unpaid = policyMember("unpaid", now, new BigDecimal("70000"),
                BigDecimal.ZERO, RegistrationStatus.PAYMENT_PENDING, event);

        // 완납자의 결제 제한만 설정하고 신규 미결제자는 구간 밖에 둔다.
        when(time.currentDateTime()).thenReturn(now);
        when(encoder.matches("test", "encoded")).thenReturn(true);
        when(repository.organizations("e", "login")).thenReturn(List.of(new Organization(
                "org", "login", "encoded", "단체", "대표자", "1990-01-01", "010-0000-0000",
                null, "주소", "상세", null, event)));
        when(repository.members("e", "org")).thenReturn(List.of(paid, unpaid));
        when(policies.loadEnabledRegistrationActionPolicies("e")).thenReturn(List.of(new Policy(
                "p", "e", Action.PAYMENT, now.minusDays(4), now.minusDays(1), now, true)));
        RegistrationQueryService service = new RegistrationQueryService(encoder, repository, resolver, time, policies);
        OrgRegistrationQueryResponse response = service.findOrganizationRegistrationDetails(
                "e", new OrganizationAccessRequest("login", "test")).getFirst();

        // 실제 반환 DTO를 직렬화하여 구 키와 구성원 결제·환불 필드가 남지 않는지 확인한다.
        JsonNode json = new ObjectMapper().valueToTree(response);
        assertThat(json.has("userPolicy")).isFalse();
        assertThat(json.get("organizationPolicy").get("paymentAllowed").asBoolean()).isTrue();
        JsonNode memberPolicy = json.get("registrations").get(1).get("memberPolicy");
        assertThat(memberPolicy.get("deleteMemberAllowed").asBoolean()).isTrue();
        assertThat(memberPolicy.has("paymentAllowed")).isFalse();
        assertThat(memberPolicy.has("refundAllowed")).isFalse();
        verify(policies, times(1)).loadEnabledRegistrationActionPolicies("e");
    }

    /** DB 필수값을 갖춘 상세 조회 행을 구성한다. */
    private Member policyMember(String id, LocalDateTime registeredAt, BigDecimal contract, BigDecimal paid,
            RegistrationStatus status, EventInput event) {
        return new Member(id, "name", null, "1990-01-01", "010-0000-0000", "encoded",
                null, "c", "category", List.of(), null, null, null, false, null, null, null,
                status, contract, paid, false, ReservationStatus.CONSUMED, null, false, registeredAt, event);
    }

    private Member member(boolean additional) {
        return new Member("r","name",null,"1990-01-01","010-0000-0000","test",null,"c","category",List.of(),
                null,null,null,false,null,null,null,additional ? RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED : RegistrationStatus.PAYMENT_PENDING,
                new BigDecimal("70000"),additional ? new BigDecimal("40000") : BigDecimal.ZERO,false,
                additional ? ReservationStatus.CONSUMED : ReservationStatus.HELD,now.minusDays(1), false, now.minusDays(2), null);
    }
}
