package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import java.math.BigDecimal;
import java.util.List;
import kr.co.teambrain.marvelrun.user.payment.command.application.AdditionalPaymentPreparationService;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.*;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.AopTestUtils;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentAllocationCreator;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 관리자 커밋 후의 추가금 상태를 구성하고 실제 사용자 준비/승인 서비스와 MySQL을 검증한다. PG는 Mock이다. */
@Import(AdditionalPaymentPreparationService.class)
class AdditionalPaymentPreparationDatabaseTest extends CapacityMvpTestSupport {
    @Autowired AdditionalPaymentPreparationService additional;

    @Test
    void personalDeferredOrderIsReusableAndPaidAfterDeadlineWithoutNewHold() {
        var initial = personal(categoryA,"S","1990-01-01");
        mockApprovalSuccess();
        payments.confirm(confirmRequest(initial.paymentId()));
        String id = initial.registrationId();
        List<String> beforeItems = itemIds(id);
        due(id,70000);
        jdbc.update("update event set payment_deadline=?,regist_deadline=?,event_status='CLOSED' where id=?",NOW.minusDays(1),NOW.minusDays(1),eventId);
        clearInvocations(toss);
        var order = additional.preparePersonal(eventId,id,access(id));
        assertThat(order.amount()).isEqualByComparingTo("30000");
        assertThat(additional.preparePersonal(eventId,id,access(id)).paymentId()).isEqualTo(order.paymentId());
        assertThat(n("select count(*) from payment where registration_id=?",id)).isEqualTo(2);
        assertThat(s("select purpose from payment where id=?",order.paymentId())).isEqualTo("ADDITIONAL_PAYMENT");
        assertThat(s("select status from registration where id=?",id)).isEqualTo("ADDITIONAL_PAYMENT_REQUIRED");
        verifyNoInteractions(toss);
        counters(total,0,1); counters(categoryACapacity,0,1); counters(shirtS,0,1);
        payments.confirm(confirmRequest(order.paymentId()));
        assertThat(s("select status from registration where id=?",id)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select paid_amount from registration where id=?",BigDecimal.class,id)).isEqualByComparingTo("70000");
        assertThat(s("select status from reservation where registration_id=?",id)).isEqualTo("CONSUMED");
        assertThat(itemIds(id)).containsExactlyElementsOf(beforeItems);
        counters(total,0,1); counters(categoryACapacity,0,1); counters(shirtS,0,1);
    }

    @Test
    void organizationPreparesOnlyOutstandingConfirmedMembers() {
        var group = group(categoryA,categoryB);
        mockApprovalSuccess(); payments.confirm(confirmRequest(group.paymentId()));
        String first = group.registrationIds().getFirst();
        due(first,70000);
        clearInvocations(toss);
        var order = additional.prepareOrganization(eventId,group.organizationId(),new OrganizationAccessRequest(
                s("select login_id from organization where id=?",group.organizationId()),"Test1234!"));
        assertThat(order.amount()).isEqualByComparingTo("30000");
        assertThat(allocationCount(order.paymentId())).isEqualTo(1);
        assertThat(allocationAmount(order.paymentId(),first)).isEqualByComparingTo("30000");
        verifyNoInteractions(toss); counters(total,0,2);
        payments.confirm(confirmRequest(order.paymentId()));
        assertThat(s("select status from registration where id=?",first)).isEqualTo("CONFIRMED");
        counters(total,0,2);
    }

    @Test
    void staleBirthCannotPrepareAfterAdminCorrection() {
        var initial = personal(categoryA,"S","1990-01-01");
        mockApprovalSuccess(); payments.confirm(confirmRequest(initial.paymentId()));
        String id = initial.registrationId();
        RegistrationAccessRequest old = access(id);
        due(id,70000);
        jdbc.update("update registration set birth='1991-01-01' where id=?",id);
        clearInvocations(toss);
        expectError(ErrorCode.REGISTRATION_ACCESS_DENIED, () -> additional.preparePersonal(eventId,id,old));
        assertThat(n("select count(*) from payment where registration_id=?",id)).isEqualTo(1);
        verifyNoInteractions(toss); counters(total,0,1);
    }

    @Test
    void allocationFailureRollsBackOrderAndPreservesConfirmedResources() {
        var initial = personal(categoryA,"S","1990-01-01");
        mockApprovalSuccess(); payments.confirm(confirmRequest(initial.paymentId()));
        String id = initial.registrationId(); due(id,70000);
        /** 실패 주입만 프록시 내부 Spy에 설정하고, 실제 주문 준비는 서비스 트랜잭션을 통한다. */
        PaymentAllocationCreator allocationSpy = AopTestUtils.getUltimateTargetObject(paymentAllocationCreator);
        assertThat(mockingDetails(allocationSpy).isSpy()).isTrue();
        doThrow(new IllegalStateException("fixture allocation failure")).when(allocationSpy).create(any(),anyList());
        clearInvocations(toss);
        assertThatThrownBy(() -> additional.preparePersonal(eventId,id,access(id)))
                .isInstanceOf(IllegalStateException.class).hasMessage("fixture allocation failure");
        assertThat(n("select count(*) from payment where registration_id=?",id)).isEqualTo(1);
        assertThat(s("select status from registration where id=?",id)).isEqualTo("ADDITIONAL_PAYMENT_REQUIRED");
        counters(total,0,1); verifyNoInteractions(toss);
    }

    @Test
    void unresolvedApprovalBlocksNewOrder() {
        var initial = personal(categoryA,"S","1990-01-01");
        mockApprovalSuccess(); payments.confirm(confirmRequest(initial.paymentId()));
        String id = initial.registrationId(); due(id,70000);
        var order = additional.preparePersonal(eventId,id,access(id));
        jdbc.update("update payment set process_status='UNKNOWN' where id=?",order.paymentId());
        clearInvocations(toss);
        assertThatThrownBy(() -> additional.preparePersonal(eventId,id,access(id)))
                .isInstanceOf(kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException.class);
        assertThat(n("select count(*) from payment where registration_id=?",id)).isEqualTo(2);
        counters(total,0,1); verifyNoInteractions(toss);
    }

    private void due(String id,int contract) {
        jdbc.update("update registration set contract_amount=?,status='ADDITIONAL_PAYMENT_REQUIRED',version=version+1 where id=?",contract,id);
    }
    private RegistrationAccessRequest access(String id) {
        return new RegistrationAccessRequest(s("select name from registration where id=?",id),
                s("select birth from registration where id=?",id),"010-0000-0000","Test1234!");
    }
}
