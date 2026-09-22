package kr.co.teambrain.marvelrun.admin.payment.command.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.teambrain.marvelrun.admin.payment.command.evidence.*;
import kr.co.teambrain.marvelrun.admin.payment.command.evidence.AdminRefundEvidenceModels;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.http.HttpMethod;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
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

    /** 저장된 취소 거래 키로만 해당 환불의 외부 완료를 확정한다. */
    @Test void evidenceMatchesExactTransactionAndAmount() {
        AdminRefundEvidenceModels.Decision decision=new AdminRefundEvidenceMatcher().compare(
                evidenceSnapshot(List.of("tx-1")),evidenceLookup("tx-1",new BigDecimal("10000")));
        assertThat(decision.verdict()).isEqualTo(AdminRefundEvidenceModels.Verdict.EXTERNAL_CANCEL_CONFIRMED);
        assertThat(decision.observation().cancels().getFirst().transactionKeyHash()).hasSize(64).isNotEqualTo("tx-1");
    }

    /** 금액만 일치하거나 키가 충돌하는 경우 확인 필요로 남긴다. */
    @Test void evidenceNeverIdentifiesCancellationByAmountOnly() {
        AdminRefundEvidenceMatcher matcher=new AdminRefundEvidenceMatcher();
        assertThat(matcher.compare(evidenceSnapshot(List.of()),evidenceLookup("tx-1",new BigDecimal("10000"))).verdict())
                .isEqualTo(AdminRefundEvidenceModels.Verdict.NOT_IDENTIFIABLE);
        assertThat(matcher.compare(evidenceSnapshot(List.of("tx-1","tx-2")),evidenceLookup("tx-1",new BigDecimal("10000"))).verdict())
                .isEqualTo(AdminRefundEvidenceModels.Verdict.NOT_IDENTIFIABLE);
        assertThat(matcher.compare(evidenceSnapshot(List.of("tx-2")),evidenceLookup("tx-1",new BigDecimal("10000"))).verdict())
                .isEqualTo(AdminRefundEvidenceModels.Verdict.KNOWN_CANCEL_NOT_OBSERVED);
        assertThat(matcher.compare(evidenceSnapshot(List.of("tx-1")),evidenceLookup("tx-1",new BigDecimal("9999"))).verdict())
                .isEqualTo(AdminRefundEvidenceModels.Verdict.RESPONSE_MISMATCH);
    }

    /** 다른 원결제 응답·중복 거래 키·조회 실패를 성공으로 판단하지 않는다. */
    @Test void evidenceRejectsWrongPaymentAndDuplicateTransaction() {
        AdminRefundEvidenceMatcher matcher=new AdminRefundEvidenceMatcher();
        TossCancelResponse good=evidenceLookup("tx-1",new BigDecimal("10000")).payment();
        TossCancelResponse wrong=new TossCancelResponse("other-key",good.orderId(),good.currency(),good.method(),
                good.status(),good.totalAmount(),good.balanceAmount(),good.lastTransactionKey(),good.cancels());
        assertThat(matcher.compare(evidenceSnapshot(List.of("tx-1")),new AdminRefundEvidenceModels.Lookup(200,null,wrong)).verdict())
                .isEqualTo(AdminRefundEvidenceModels.Verdict.RESPONSE_MISMATCH);
        TossCancelResponse duplicate=new TossCancelResponse(good.paymentKey(),good.orderId(),good.currency(),good.method(),
                good.status(),good.totalAmount(),good.balanceAmount(),good.lastTransactionKey(),List.of(good.cancels().getFirst(),good.cancels().getFirst()));
        assertThat(matcher.compare(evidenceSnapshot(List.of("tx-1")),new AdminRefundEvidenceModels.Lookup(200,null,duplicate)).verdict())
                .isEqualTo(AdminRefundEvidenceModels.Verdict.RESPONSE_MISMATCH);
        assertThat(matcher.compare(evidenceSnapshot(List.of("tx-1")),new AdminRefundEvidenceModels.Lookup(404,"TOSS_LOOKUP_HTTP_ERROR",null)).verdict())
                .isEqualTo(AdminRefundEvidenceModels.Verdict.LOOKUP_UNAVAILABLE);
    }

    /** 외부 호출은 GET 1회이며 취소 POST나 자동 재시도를 하지 않는다. */
    @Test void evidenceClientUsesOnlyGetAndSanitizesHttpErrors() {
        RestClient.Builder builder=RestClient.builder().baseUrl("https://example.test");
        MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        AdminRefundEvidenceClient client=new AdminRefundEvidenceClient(builder.build());
        server.expect(requestTo("https://example.test/v1/payments/key"))
                .andExpect(method(HttpMethod.GET)).andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON).body("{\"secretKey\":\"do-not-store\"}"));
        AdminRefundEvidenceModels.Lookup result=client.lookup("key");
        assertThat(result.httpStatus()).isEqualTo(401);
        assertThat(result.errorCode()).isEqualTo("TOSS_LOOKUP_HTTP_ERROR");
        assertThat(result.payment()).isNull();
        server.verify();
    }

    /** 성공 GET의 불필요한 개인정보 필드는 증거 DTO에 포함하지 않는다. */
    @Test void evidenceClientReadsPaymentAndIgnoresCustomerFields() {
        RestClient.Builder builder=RestClient.builder().baseUrl("https://example.test");
        MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        AdminRefundEvidenceClient client=new AdminRefundEvidenceClient(builder.build());
        server.expect(requestTo("https://example.test/v1/payments/key")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"paymentKey":"key","orderId":"order","currency":"KRW","status":"DONE",
                         "totalAmount":70000,"balanceAmount":70000,"customerName":"private","cancels":null}
                        """,MediaType.APPLICATION_JSON));
        assertThat(client.lookup("key").payment().orderId()).isEqualTo("order");
        server.verify();
    }

    /** 조회 도중 서버 상태 변경을 감지하면 외부 완료 단정 대신 시점 불일치를 저장한다. */
    @Test void evidencePersistsLocalChangeWithoutCallingRefundExecutor() {
        AdminRefundEvidenceStore evidenceStore=mock(AdminRefundEvidenceStore.class);
        AdminRefundEvidenceClient client=mock(AdminRefundEvidenceClient.class);
        AdminRefundTime clock=mock(AdminRefundTime.class);
        when(clock.now()).thenReturn(now);
        AdminRefundEvidenceModels.Snapshot before=evidenceSnapshot(List.of("tx-1"));
        AdminRefundEvidenceModels.Snapshot after=new AdminRefundEvidenceModels.Snapshot(before.paymentCancelId(),before.paymentId(),
                before.paymentKey(),before.orderId(),before.totalAmount(),before.cancelAmount(),"SUCCEEDED",before.transactionKeys());
        when(evidenceStore.snapshot("event","cancel")).thenReturn(before,after);
        when(client.lookup("key")).thenReturn(evidenceLookup("tx-1",new BigDecimal("10000")));
        when(evidenceStore.append(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AdminRefundEvidenceService service=new AdminRefundEvidenceService(evidenceStore,client,new AdminRefundEvidenceMatcher(),clock);
        AdminRefundEvidenceModels.Evidence evidence=service.check("event","cancel","admin");
        assertThat(evidence.verdict()).isEqualTo(AdminRefundEvidenceModels.Verdict.LOCAL_CHANGED);
        assertThat(evidence.financialStateChanged()).isFalse();
        assertThat(evidence.retryAllowed()).isFalse();
        verify(evidenceStore).append(evidence);
        verifyNoInteractions(execution,preparation);
    }

    /** 잘못된 소속은 외부 요청 전에 차단하고 증거 저장 실패는 성공으로 숨기지 않는다. */
    @Test void evidenceStopsOnScopeFailureAndPropagatesJournalFailure() {
        AdminRefundEvidenceStore evidenceStore=mock(AdminRefundEvidenceStore.class);
        AdminRefundEvidenceClient client=mock(AdminRefundEvidenceClient.class);
        AdminRefundTime clock=mock(AdminRefundTime.class);
        AdminRefundEvidenceService service=new AdminRefundEvidenceService(evidenceStore,client,new AdminRefundEvidenceMatcher(),clock);
        when(evidenceStore.snapshot("wrong","cancel")).thenThrow(new IllegalArgumentException("scope"));
        assertThatThrownBy(() -> service.check("wrong","cancel","admin")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
        when(evidenceStore.snapshot("event","cancel")).thenReturn(evidenceSnapshot(List.of()));
        when(clock.now()).thenReturn(now);
        when(client.lookup("key")).thenReturn(new AdminRefundEvidenceModels.Lookup(null,"TIMEOUT",null));
        when(evidenceStore.append(any())).thenThrow(new IllegalStateException("journal unavailable"));
        assertThatThrownBy(() -> service.check("event","cancel","admin")).isInstanceOf(IllegalStateException.class);
        verify(client,times(1)).lookup("key");
    }

    /** 일반 사용자 principal로 외부 증거 수집을 실행할 수 없다. */
    @Test void evidenceControllerRequiresAdmin() {
        AdminRefundEvidenceService service=mock(AdminRefundEvidenceService.class);
        AdminRefundEvidenceStore evidenceStore=mock(AdminRefundEvidenceStore.class);
        AdminRefundEvidenceController controller=new AdminRefundEvidenceController(service,evidenceStore);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("user",null,List.of()));
        assertThatThrownBy(() -> controller.check("event","cancel")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(service,evidenceStore);
    }

    /** 민감한 키는 내부 스냅샷에만 들어간다. */
    private AdminRefundEvidenceModels.Snapshot evidenceSnapshot(List<String> keys) {
        return new AdminRefundEvidenceModels.Snapshot("cancel","payment","key","order",new BigDecimal("70000"),
                new BigDecimal("10000"),"UNKNOWN",keys);
    }
    /** 취소 배열의 정확한 거래 키 대조를 위한 제한된 외부 응답이다. */
    private AdminRefundEvidenceModels.Lookup evidenceLookup(String transactionKey,BigDecimal amount) {
        return new AdminRefundEvidenceModels.Lookup(200,null,new TossCancelResponse("key","order","KRW","카드",
                "PARTIAL_CANCELED",new BigDecimal("70000"),new BigDecimal("60000"),transactionKey,
                List.of(new TossCancelResponse.Cancel(transactionKey,amount,new BigDecimal("60000"),"DONE",OffsetDateTime.parse("2026-09-23T12:00:00+09:00")))));
    }

}
