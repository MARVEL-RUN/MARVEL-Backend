package kr.co.teambrain.marvelrun.admin.payment.command.application;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.admin.payment.command.infrastructure.toss.refund.*;
import static org.assertj.core.api.Assertions.*;
/** 금융 상태 변경 없이 기록하는 외부·내부 결과 비교 계약을 검증한다. */
class PaymentResultLogMetadataTest {
    /** 양쪽의 확정/불명 조합을 누락 없이 구분한다. */
    @ParameterizedTest
    @CsvSource(value={"true,true,SUCCESS", "true,false,MISMATCH", "false,true,MISMATCH", "false,false,FAILED",
            "null,true,UNVERIFIED", "null,false,UNVERIFIED", "true,null,UNVERIFIED", "false,null,UNVERIFIED", "null,null,UNVERIFIED"},nullValues="null")
    void classifiesOnlyKnownEvidence(Boolean external, Boolean local, String expected) {
        assertThat(PaymentResultLogMetadata.classify(external,local).name()).isEqualTo(expected);
    }
    /** UNKNOWN에 실린 검증 성공 증거는 결과불명으로 덮지 않고 불일치로 남긴다. */
    @Test
    void retainsVerifiedEvidenceAfterLocalFailureWithoutMutatingOldMetadata() {
        Map<String,Object> old = Map.of("amount",new BigDecimal("10000"),"status","UNKNOWN");
        VerifiedTossCancellation proof = new VerifiedTossCancellation("transaction",new BigDecimal("10000"),new BigDecimal("30000"),OffsetDateTime.parse("2026-09-23T12:00:00+09:00"),"PARTIAL_CANCELED");
        TossCancelOutcome outcome = new TossCancelOutcome(TossCancelOutcome.Kind.UNKNOWN,proof,200,"CANCEL_RESULT_SAVE_FAILED","local failure");
        Map<String,Object> saved = PaymentResultLogMetadata.refund(old,PaymentCancelStatus.UNKNOWN,outcome);
        assertThat(saved).containsAllEntriesOf(old);
        assertThat(old).doesNotContainKey("resultComparison");
        assertThat(PaymentResultLogMetadata.readStatus(saved)).isEqualTo(PaymentResultComparison.MISMATCH);
        assertThat(((Map<?,?>)saved.get("resultComparison")).get("externalState")).isEqualTo("PARTIAL_CANCELED");
    }
    /** 증거가 없는 과거 로그와 미지원 버전·불일치 판별값은 성공으로 소급하지 않는다. */
    @Test
    void legacyAndInvalidComparisonAreUnverified() {
        assertThat(PaymentResultLogMetadata.readStatus(null)).isEqualTo(PaymentResultComparison.UNVERIFIED);
        assertThat(PaymentResultLogMetadata.readStatus(Map.of("status","DONE"))).isEqualTo(PaymentResultComparison.UNVERIFIED);
        Map<String,Object> comparison = new LinkedHashMap<>(Map.of("schemaVersion",2,"status","SUCCESS","externalTargetReached",true,"localTargetReached",true));
        assertThat(PaymentResultLogMetadata.readStatus(Map.of("resultComparison",comparison))).isEqualTo(PaymentResultComparison.UNVERIFIED);
        comparison.put("schemaVersion",1); comparison.put("externalTargetReached",false);
        assertThat(PaymentResultLogMetadata.readStatus(Map.of("resultComparison",comparison))).isEqualTo(PaymentResultComparison.UNVERIFIED);
    }
    /** 기존 로그 DTO 생성자는 그대로 사용하고 JSON 응답에 판별값만 추가한다. */
    @Test
    void serializesComparisonAndPreservesMetadataFiltering() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String,Object> old = Map.of("paymentKey","secret-value","amount",10000);
        Map<String,Object> metadata = PaymentResultLogMetadata.append(old,"CANCEL",true,false,"UNKNOWN","CANCEL_RESPONSE","PARTIAL_CANCELED",Map.of("cancelAmount",10000));
        kr.co.teambrain.marvelrun.admin.payment.query.AdminPaymentLogMetadata filter = new kr.co.teambrain.marvelrun.admin.payment.query.AdminPaymentLogMetadata(mapper);
        Map<String,Object> clean = filter.read(mapper.writeValueAsString(metadata));
        assertThat(clean).doesNotContainKey("paymentKey");
        kr.co.teambrain.marvelrun.admin.payment.query.AdminPaymentQueryResponse.Log log = new kr.co.teambrain.marvelrun.admin.payment.query.AdminPaymentQueryResponse.Log(null,"order","CANCEL_UNKNOWN","ADMIN",200,null,null,clean);
        assertThat(mapper.readTree(mapper.writeValueAsString(log)).get("comparisonStatus").asText()).isEqualTo("MISMATCH");
        kr.co.teambrain.marvelrun.admin.payment.query.AdminPaymentQueryResponse.Log legacy = new kr.co.teambrain.marvelrun.admin.payment.query.AdminPaymentQueryResponse.Log(null,"order","CONFIRM_SUCCEEDED","API",200,null,null,null);
        assertThat(mapper.readTree(mapper.writeValueAsString(legacy)).get("comparisonStatus").asText()).isEqualTo("UNVERIFIED");
        assertThat(filter.read("broken-json")).containsEntry("metadataUnreadable",true);
    }

}
