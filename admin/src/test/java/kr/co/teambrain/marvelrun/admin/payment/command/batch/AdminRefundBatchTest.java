package kr.co.teambrain.marvelrun.admin.payment.command.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import kr.co.teambrain.marvelrun.admin.security.details.CustomAdminDetail;
import kr.co.teambrain.marvelrun.admin.payment.command.*;
import kr.co.teambrain.marvelrun.admin.payment.command.dto.*;
import kr.co.teambrain.marvelrun.admin.payment.command.application.refund.*;
import kr.co.teambrain.marvelrun.admin.payment.command.infrastructure.toss.refund.*;
import kr.co.teambrain.marvelrun.admin.payment.command.batch.AdminRefundBatchModels.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 배치 접수 계약과 실패 경계를 한 클래스에서 검증한다. 금융 동작은 기존 DB 테스트를 확장한다. */
class AdminRefundBatchTest {
    private static final ValidatorFactory VALIDATION=Validation.buildDefaultValidatorFactory();
    private final AdminRefundBatchStore store=mock(AdminRefundBatchStore.class);
    private final AdminRefundPreparationService preparation=mock(AdminRefundPreparationService.class);
    private final AdminRefundExecutionService execution=mock(AdminRefundExecutionService.class);
    private final AdminRefundBatchWorker worker=new AdminRefundBatchWorker(store,preparation,execution);
    private final LocalDateTime now=LocalDateTime.of(2026,9,23,12,0);
    private final Work work=new Work("batch",0,"event","admin","request","사유",Operation.FULL,new Target("r",null,0L,null,null));
    private final AdminRefundPrepared prepared=new AdminRefundPrepared("request","correlation","event",null,now,List.of(),
            List.of(new AdminRefundPrepared.Refund("cancel","payment",new BigDecimal("10000"),PaymentCancelType.FULL,PaymentCancelStatus.PROCESSING)));

    /** 테스트 인증을 다음 테스트에 남기지 않는다. */
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    /** 검증 제공자 자원을 정리한다. */
    @AfterAll static void close() { VALIDATION.close(); }

    /** 같은 요청의 배열 순서 차이는 동일한 fingerprint를 사용하며 DB를 재확장하지 않는다. */
    @Test void canonicalRequestReusesBatchBeforeSelection() {
        AdminRefundBatchSelection selection=mock(AdminRefundBatchSelection.class);
        AdminRefundBatchService service=new AdminRefundBatchService(store,selection,new ObjectMapper(),VALIDATION.getValidator(),worker);
        when(store.existing(eq("event"),eq("request"),anyString(),eq("admin"))).thenReturn("batch");
        service.full("event","admin",new AdminPaymentRefundRequest("request","사유",List.of("b","a"),List.of()));
        service.full("event","admin",new AdminPaymentRefundRequest("request","사유",List.of("a","b","a"),List.of()));
        ArgumentCaptor<String> hashes=ArgumentCaptor.forClass(String.class);
        verify(store,times(2)).existing(eq("event"),eq("request"),hashes.capture(),eq("admin"));
        assertThat(hashes.getAllValues().get(0)).isEqualTo(hashes.getAllValues().get(1));
        verifyNoInteractions(selection);
        verify(store,never()).create(any(),any(),any(),any(),any(),any(),any());
    }

    /** 외부 결과와 저장 여부를 함께 확인해야 성공으로 집계한다. */
    @ParameterizedTest
    @CsvSource({"VERIFIED,true,SUCCEEDED","REJECTED,true,FAILED","UNKNOWN,true,NEEDS_REVIEW","VERIFIED,false,NEEDS_REVIEW"})
    void classifiesResultsWithoutRetry(String kind,boolean stored,String status) {
        when(store.claim("batch")).thenReturn(work);
        when(preparation.prepareFull(eq("event"),isNull(),eq(List.of("r")),any())).thenReturn(prepared);
        TossCancelOutcome outcome=switch (kind) {
            case "VERIFIED" -> TossCancelOutcome.verified(new VerifiedTossCancellation("transaction",new BigDecimal("10000"),BigDecimal.ZERO,OffsetDateTime.parse("2026-09-23T12:00:00+09:00"),"CANCELED"));
            case "REJECTED" -> TossCancelOutcome.rejected(400,"REJECTED");
            default -> TossCancelOutcome.unknown(null,"TIMEOUT");
        };
        AdminRefundExecutionService.Result result=new AdminRefundExecutionService.Result("request","correlation",now,now,
                List.of(new AdminRefundExecutionResult("cancel",true,outcome,stored,!stored,null)));
        when(execution.execute(prepared)).thenReturn(result);
        worker.processOne("batch");
        org.mockito.InOrder order=inOrder(store,preparation,execution);
        order.verify(store).claim("batch");
        order.verify(preparation).prepareFull(eq("event"),isNull(),eq(List.of("r")),any());
        order.verify(store).prepared(work,prepared);
        order.verify(execution).execute(prepared);
        order.verify(store).finish(eq(work),eq(status),nullable(String.class),same(result));
        verify(execution,times(1)).execute(prepared);
    }

    /** 준비 결과 저장에 실패하면 외부 환불을 시작하지 않고 예외로 수집한다. */
    @Test void journalFailureStopsBeforeToss() {
        when(store.claim("batch")).thenReturn(work);
        when(preparation.prepareFull(any(),isNull(),any(),any())).thenReturn(prepared);
        doThrow(new IllegalStateException("db failure")).when(store).prepared(work,prepared);
        worker.processOne("batch");
        verifyNoInteractions(execution);
        verify(store).finish(eq(work),eq("NEEDS_REVIEW"),eq("PREPARATION_OR_JOURNAL_ERROR"),any());
    }

    /** 이미 선점한 작업은 다른 실행기가 요청을 보내지 못한다. */
    @Test void unclaimedWorkDoesNotExecute() {
        when(store.claim("batch")).thenReturn(null);
        worker.processOne("batch");
        verifyNoInteractions(preparation,execution);
    }

    /** 수기 환불 금액은 무시하지 않고 HTTP 400으로 거절한다. 정상 birth 후보는 서비스로 전달한다. */
    @Test void controllerRejectsAmountAndAcceptsBirthCandidate() throws Exception {
        AdminRefundBatchService service=mock(AdminRefundBatchService.class);
        ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new AdminRefundBatchController(service,store,mapper)).build();
        CustomAdminDetail detail=new CustomAdminDetail("admin","관리자","ADMIN");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(detail,null,detail.getAuthorities()));
        String body="""
                {"requestId":"request","reason":"생일 정정","targets":[
                 {"registrationId":"r","eventCategoryId":"c","birth":"2015-01-01",
                  "selectedSouvenirList":[{"souvenirId":"s","selectedSize":"M"}]}]}
                """;
        mvc.perform(post("/v1/admin/events/event/payment-partial-refunds").contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("\"birth\":", "\"refundAmount\":10000,\"birth\":")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        mvc.perform(post("/v1/admin/events/event/payment-partial-refunds").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        ArgumentCaptor<AdminPaymentPartialRefundRequest> request=ArgumentCaptor.forClass(AdminPaymentPartialRefundRequest.class);
        verify(service).partial(eq("event"),eq("admin"),request.capture());
        assertThat(request.getValue().targets().getFirst().birth()).isEqualTo("2015-01-01");
    }

    /** 일반 사용자 형태의 principal은 관리자 환불을 실행할 수 없다. */
    @Test void controllerRejectsNonAdminPrincipal() throws Exception {
        AdminRefundBatchService service=mock(AdminRefundBatchService.class);
        AdminRefundBatchController controller=new AdminRefundBatchController(service,store,new ObjectMapper());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("user",null,List.of()));
        assertThatThrownBy(() -> controller.summary("event","batch")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(service,store);
    }
    /** 기존 요청 조회는 미처리가 남아 있어도 자동 실행하지 않는다. */
    @Test void duplicateRequestNeverResumesPendingWork() {
        AdminRefundBatchSelection selection=mock(AdminRefundBatchSelection.class);
        AdminRefundBatchWorker runner=mock(AdminRefundBatchWorker.class);
        AdminRefundBatchService service=new AdminRefundBatchService(store,selection,new ObjectMapper(),VALIDATION.getValidator(),runner);
        when(store.existing(eq("event"),eq("request"),anyString(),eq("admin"))).thenReturn("batch");
        service.full("event","admin",new AdminPaymentRefundRequest("request","사유",List.of("r"),List.of()));
        verifyNoInteractions(runner,selection);
        verify(store).response("event","batch");
    }

    /** 접수 저장 완료 후 동일 스레드에서 각 대상을 처리하고 마지막에 응답한다. */
    @Test void processesAllTargetsBeforeReturningResponse() {
        AdminRefundBatchSelection selection=mock(AdminRefundBatchSelection.class);
        AdminRefundBatchWorker runner=mock(AdminRefundBatchWorker.class);
        AdminRefundBatchService service=new AdminRefundBatchService(store,selection,new ObjectMapper(),VALIDATION.getValidator(),runner);
        when(selection.full(eq("event"),any())).thenReturn(List.of(work.target(),new Target("r2",null,0L,null,null)));
        when(store.create(eq("event"),eq("request"),eq("admin"),any(),eq(Operation.FULL),any(),any())).thenReturn("batch");
        when(runner.processOne("batch")).thenReturn(true);
        service.full("event","admin",new AdminPaymentRefundRequest("request","사유",List.of("r","r2"),List.of()));
        org.mockito.InOrder order=inOrder(store,runner);
        order.verify(store).existing(eq("event"),eq("request"),anyString(),eq("admin"));
        order.verify(store).create(eq("event"),eq("request"),eq("admin"),any(),eq(Operation.FULL),any(),any());
        order.verify(runner,times(2)).processOne("batch");
        order.verify(store).response("event","batch");
    }

    /** 중복 INSERT에서 진 호출은 기존 결과를 조회하며 토스 실행권을 얻지 않는다. */
    @Test void duplicateInsertReturnsExistingWithoutExecution() {
        AdminRefundBatchSelection selection=mock(AdminRefundBatchSelection.class);
        AdminRefundBatchWorker runner=mock(AdminRefundBatchWorker.class);
        AdminRefundBatchService service=new AdminRefundBatchService(store,selection,new ObjectMapper(),VALIDATION.getValidator(),runner);
        when(store.existing(eq("event"),eq("request"),anyString(),eq("admin"))).thenReturn(null,"batch");
        when(selection.full(eq("event"),any())).thenReturn(List.of(work.target()));
        when(store.create(any(),any(),any(),any(),any(),any(),any())).thenThrow(new org.springframework.dao.DuplicateKeyException("duplicate"));
        service.full("event","admin",new AdminPaymentRefundRequest("request","사유",List.of("r"),List.of()));
        verifyNoInteractions(runner);
        verify(store).response("event","batch");
    }

    /** 최종 기록 실패는 이후 대상 실행을 멈추게 하며 성공으로 숨기지 않는다. */
    @Test void finalJournalFailureStopsExecutionLoop() {
        when(store.claim("batch")).thenReturn(work);
        when(preparation.prepareFull(any(),isNull(),any(),any())).thenThrow(new IllegalStateException("unknown commit"));
        doThrow(new IllegalStateException("db unavailable")).when(store).finish(any(),any(),any(),any());
        assertThat(worker.processOne("batch")).isFalse();
        verifyNoInteractions(execution);
    }

    /** OSIV 활성 상태에서 긴 동기 처리를 시작하지 않는다. */
    @Test void requiresOsivDisabled() {
        assertThatThrownBy(() -> new AdminRefundBatchConfiguration(true)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new AdminRefundBatchConfiguration(false)).doesNotThrowAnyException();
    }

}
