package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.*;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.RegistrationCreateResponse;
import kr.co.teambrain.marvelrun.user.payment.command.application.AdditionalPaymentPreparationService;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentAllocationCreator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.AopTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 관리자 커밋 후의 추가금 상태를 구성하고 실제 사용자 준비/승인 서비스와 MySQL을 검증한다. PG는 Mock이다. */
@Import(AdditionalPaymentPreparationService.class)
class AdditionalPaymentPreparationDatabaseTest extends CapacityMvpTestSupport {
    @Autowired AdditionalPaymentPreparationService additional;

    /** 구간별 추가결제 제한은 주문 생성과 PG 호출 전에 적용한다. */
    @Test
    void periodPolicyBlocksAdditionalPreparationWithoutCreatingOrder() {
        RegistrationCreateResponse initial = createPersonalRegistration(categoryA, "S", "1990-01-01");
        mockApprovalSuccess();
        payments.confirm(confirmRequest(initial.paymentId()));
        markRegistrationAsAdditionalPaymentRequired(initial.registrationId(), 70000);
        jdbc.update("update registration set registration_date=? where id=?", NOW, initial.registrationId());
        jdbc.update("insert into registration_action_policy(id,event_id,action_type,registration_start_at,registration_end_at,effective_from,enabled) values(?,?,'PAYMENT',?,?,?,true)",
                UUID.randomUUID().toString(), eventId, NOW.minusDays(1), NOW.plusDays(1), NOW);
        int before = queryIntegerValue("select count(*) from payment where registration_id=?", initial.registrationId());
        List<String> beforeItems = itemIds(initial.registrationId());
        clearInvocations(toss);

        expectError(ErrorCode.REGISTRATION_PAYMENT_POLICY_BLOCKED, () -> additional.preparePersonal(
                eventId, initial.registrationId(), createRegistrationAccessRequest(initial.registrationId())));
        assertThat(queryIntegerValue("select count(*) from payment where registration_id=?", initial.registrationId())).isEqualTo(before);
        assertThat(itemIds(initial.registrationId())).isEqualTo(beforeItems);
        verifyNoInteractions(toss);
    }

    /** 기한 전 준비된 추가 주문도 기한 이후 새 승인 시도는 PG 호출 전에 거절한다. */
    @Test
    void additionalApprovalAfterDeadlineIsBlockedWithoutChangingReadyOrder() {
        RegistrationCreateResponse initial = createPersonalRegistration(categoryA, "S", "1990-01-01");
        mockApprovalSuccess();
        payments.confirm(confirmRequest(initial.paymentId()));
        markRegistrationAsAdditionalPaymentRequired(initial.registrationId(), 70000);
        RegistrationModificationSettlementResult.Order order =
                additional.preparePersonal(eventId, initial.registrationId(), createRegistrationAccessRequest(initial.registrationId()));
        jdbc.update("update event set payment_deadline=? where id=?", NOW, eventId);
        clearInvocations(toss);
        expectError(ErrorCode.EVENT_PAYMENT_CLOSED, () -> payments.confirm(confirmRequest(order.paymentId())));
        assertThat(queryStringValue("select process_status from payment where id=?", order.paymentId())).isEqualTo("READY");
        verifyNoInteractions(toss);
    }

    /* 외부 결제 신청에 미납액이 있더라도 추가 주문 생성과 재사용을 차단한다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsExternalPaymentBeforeCreatingOrReusingAdditionalOrder(boolean existingOrder) {
        // 정상 추가금 fixture에서 주문 유무만 달리한 뒤 외부 결제로 전환한다.
        RegistrationCreateResponse initial = createPersonalRegistration(categoryA, "S", "1990-01-01");
        mockApprovalSuccess();
        payments.confirm(confirmRequest(initial.paymentId()));
        String id = initial.registrationId();
        markRegistrationAsAdditionalPaymentRequired(id, 70000);
        if (existingOrder) {
            additional.preparePersonal(eventId, id, createRegistrationAccessRequest(id));
        }
        jdbc.update("update registration set external_payment=1 where id=?", id);
        int orderCount = queryIntegerValue("select count(*) from payment where registration_id=?", id);
        List<String> beforeItems = itemIds(id);
        clearInvocations(toss);

        // 기존 확정 예약과 미납액을 보존하며 외부 승인 호출을 하지 않는다.
        expectError(ErrorCode.EXTERNAL_PAYMENT_REGISTRATION_RESTRICTED,
                () -> additional.preparePersonal(eventId, id, createRegistrationAccessRequest(id)));
        assertThat(queryIntegerValue("select count(*) from payment where registration_id=?", id)).isEqualTo(orderCount);
        assertThat(itemIds(id)).containsExactlyElementsOf(beforeItems);
        assertThat(queryStringValue("select status from registration where id=?", id)).isEqualTo("ADDITIONAL_PAYMENT_REQUIRED");
        assertCapacityCounts(total, 0, 1);
        verifyNoInteractions(toss);
    }

    @Test
    void personalDeferredOrderIsReusableBeforeDeadlineWithoutNewHold() {
        var initial = createPersonalRegistration(categoryA,"S","1990-01-01");
        mockApprovalSuccess();
        payments.confirm(confirmRequest(initial.paymentId()));
        String id = initial.registrationId();
        List<String> beforeItems = itemIds(id);
        markRegistrationAsAdditionalPaymentRequired(id,70000);
        jdbc.update("update event set payment_deadline=?,regist_deadline=?,event_status='CLOSED' where id=?",NOW.plusDays(1),NOW.minusDays(1),eventId);
        clearInvocations(toss);
        var order = additional.preparePersonal(eventId,id,createRegistrationAccessRequest(id));
        assertThat(order.amount()).isEqualByComparingTo("30000");
        assertThat(additional.preparePersonal(eventId,id,createRegistrationAccessRequest(id)).paymentId()).isEqualTo(order.paymentId());
        assertThat(queryIntegerValue("select count(*) from payment where registration_id=?",id)).isEqualTo(2);
        assertThat(queryStringValue("select purpose from payment where id=?",order.paymentId())).isEqualTo("ADDITIONAL_PAYMENT");
        assertThat(queryStringValue("select status from registration where id=?",id)).isEqualTo("ADDITIONAL_PAYMENT_REQUIRED");
        verifyNoInteractions(toss);
        assertCapacityCounts(total,0,1); assertCapacityCounts(categoryACapacity,0,1); assertCapacityCounts(shirtS,0,1);
        payments.confirm(confirmRequest(order.paymentId()));
        assertThat(queryStringValue("select status from registration where id=?",id)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select paid_amount from registration where id=?",BigDecimal.class,id)).isEqualByComparingTo("70000");
        assertThat(queryStringValue("select status from reservation where registration_id=?",id)).isEqualTo("CONSUMED");
        assertThat(itemIds(id)).containsExactlyElementsOf(beforeItems);
        assertCapacityCounts(total,0,1); assertCapacityCounts(categoryACapacity,0,1); assertCapacityCounts(shirtS,0,1);
    }

    @Test
    void organizationPreparesOnlyOutstandingConfirmedMembers() {
        var group = createOrganizationRegistration(categoryA,categoryB);
        mockApprovalSuccess(); payments.confirm(confirmRequest(group.paymentId()));
        String first = group.registrationIds().getFirst();
        markRegistrationAsAdditionalPaymentRequired(first,70000);
        clearInvocations(toss);
        var order = additional.prepareOrganization(eventId,group.organizationId(),new OrganizationAccessRequest(
                queryStringValue("select login_id from organization where id=?",group.organizationId()),"Test1234!"));
        assertThat(order.amount()).isEqualByComparingTo("30000");
        assertThat(allocationCount(order.paymentId())).isEqualTo(1);
        assertThat(allocationAmount(order.paymentId(),first)).isEqualByComparingTo("30000");
        verifyNoInteractions(toss); assertCapacityCounts(total,0,2);
        payments.confirm(confirmRequest(order.paymentId()));
        assertThat(queryStringValue("select status from registration where id=?",first)).isEqualTo("CONFIRMED");
        assertCapacityCounts(total,0,2);
    }

    @Test
    void staleBirthCannotPrepareAfterAdminCorrection() {
        var initial = createPersonalRegistration(categoryA,"S","1990-01-01");
        mockApprovalSuccess(); payments.confirm(confirmRequest(initial.paymentId()));
        String id = initial.registrationId();
        RegistrationAccessRequest old = createRegistrationAccessRequest(id);
        markRegistrationAsAdditionalPaymentRequired(id,70000);
        jdbc.update("update registration set birth='1991-01-01' where id=?",id);
        clearInvocations(toss);
        expectError(ErrorCode.REGISTRATION_ACCESS_DENIED, () -> additional.preparePersonal(eventId,id,old));
        assertThat(queryIntegerValue("select count(*) from payment where registration_id=?",id)).isEqualTo(1);
        verifyNoInteractions(toss); assertCapacityCounts(total,0,1);
    }

    @Test
    void allocationFailureRollsBackOrderAndPreservesConfirmedResources() {
        var initial = createPersonalRegistration(categoryA,"S","1990-01-01");
        mockApprovalSuccess(); payments.confirm(confirmRequest(initial.paymentId()));
        String id = initial.registrationId(); markRegistrationAsAdditionalPaymentRequired(id,70000);
        /** 실패 주입만 프록시 내부 Spy에 설정하고, 실제 주문 준비는 서비스 트랜잭션을 통한다. */
        PaymentAllocationCreator allocationSpy = AopTestUtils.getUltimateTargetObject(paymentAllocationCreator);
        assertThat(mockingDetails(allocationSpy).isSpy()).isTrue();
        doThrow(new IllegalStateException("fixture allocation failure")).when(allocationSpy).create(any(),anyList());
        clearInvocations(toss);
        assertThatThrownBy(() -> additional.preparePersonal(eventId,id,createRegistrationAccessRequest(id)))
                .isInstanceOf(IllegalStateException.class).hasMessage("fixture allocation failure");
        assertThat(queryIntegerValue("select count(*) from payment where registration_id=?",id)).isEqualTo(1);
        assertThat(queryStringValue("select status from registration where id=?",id)).isEqualTo("ADDITIONAL_PAYMENT_REQUIRED");
        assertCapacityCounts(total,0,1); verifyNoInteractions(toss);
    }

    @Test
    void unresolvedApprovalBlocksNewOrder() {
        var initial = createPersonalRegistration(categoryA,"S","1990-01-01");
        mockApprovalSuccess(); payments.confirm(confirmRequest(initial.paymentId()));
        String id = initial.registrationId(); markRegistrationAsAdditionalPaymentRequired(id,70000);
        var order = additional.preparePersonal(eventId,id,createRegistrationAccessRequest(id));
        jdbc.update("update payment set process_status='UNKNOWN' where id=?",order.paymentId());
        clearInvocations(toss);
        assertThatThrownBy(() -> additional.preparePersonal(eventId,id,createRegistrationAccessRequest(id)))
                .isInstanceOf(CustomException.class);
        assertThat(queryIntegerValue("select count(*) from payment where registration_id=?",id)).isEqualTo(2);
        assertCapacityCounts(total,0,1); verifyNoInteractions(toss);
    }

    /** 확정 신청을 지정 계약금액의 추가결제 필요 상태로 준비한다. */
    private void markRegistrationAsAdditionalPaymentRequired(String id,int contract) {
        jdbc.update("update registration set contract_amount=?,status='ADDITIONAL_PAYMENT_REQUIRED',version=version+1 where id=?",contract,id);
    }
    /** 테스트 신청에 저장된 본인확인 정보로 접근 요청을 구성한다. */
    private RegistrationAccessRequest createRegistrationAccessRequest(String id) {
        return new RegistrationAccessRequest(queryStringValue("select name from registration where id=?",id),
                queryStringValue("select birth from registration where id=?",id),"010-0000-0000","Test1234!");
    }
}
