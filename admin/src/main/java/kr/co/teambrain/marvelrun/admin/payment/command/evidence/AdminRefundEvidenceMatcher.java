package kr.co.teambrain.marvelrun.admin.payment.command.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import kr.co.teambrain.marvelrun.admin.payment.command.infrastructure.toss.refund.TossCancelResponse;
import kr.co.teambrain.marvelrun.admin.payment.command.evidence.AdminRefundEvidenceModels.*;

/** 금액·시각 추측으로 특정 취소를 성공 처리하지 않는 보수적 증거 대조기다. */
@Component
public class AdminRefundEvidenceMatcher {
    /** 외부 조회 당시 결과만 판단한다. 관찰되지 않았다는 사실은 환불 실패의 증명이 아니다. */
    public Decision compare(Snapshot local, Lookup lookup) {
        TossCancelResponse payment=lookup.payment();
        if (payment == null || !Integer.valueOf(200).equals(lookup.httpStatus())) { return new Decision(Verdict.LOOKUP_UNAVAILABLE,"외부 결과를 확인하지 못했습니다.",null); }
        boolean identity=Objects.equals(local.paymentKey(),payment.paymentKey())
                && local.orderId()!=null && local.orderId().equals(payment.orderId())
                && equal(local.totalAmount(),payment.totalAmount()) && "KRW".equals(payment.currency())
                && ("카드".equals(payment.method()) || "간편결제".equals(payment.method()))
                && local.totalAmount()!=null && local.totalAmount().signum()>0
                && local.cancelAmount()!=null && local.cancelAmount().signum()>0;
        List<TossCancelResponse.Cancel> cancels=payment.cancels()==null ? List.of() : payment.cancels();
        boolean malformed=cancels.size()>1000 || cancels.stream().anyMatch(Objects::isNull);
        List<ObservedCancel> observed=cancels.stream().filter(Objects::nonNull).limit(1000)
                .map(c -> new ObservedCancel(hash(c.transactionKey()),c.cancelAmount(),c.cancelStatus(),c.canceledAt())).toList();
        Observation observation=new Observation(payment.status(),payment.totalAmount(),payment.balanceAmount(),identity,observed);
        if (!identity || malformed) { return decision(Verdict.RESPONSE_MISMATCH,"원결제 식별값 또는 응답 구조가 일치하지 않습니다.",observation); }
        if (local.transactionKeys().size()!=1) {
            return decision(Verdict.NOT_IDENTIFIABLE,"특정 환불과 대조할 거래 키가 없거나 서로 충돌합니다. 금액만으로 확정하지 않습니다.",observation);
        }
        String key=local.transactionKeys().getFirst();
        List<TossCancelResponse.Cancel> matches=cancels.stream().filter(c -> key.equals(c.transactionKey())).toList();
        if (matches.isEmpty()) { return decision(Verdict.KNOWN_CANCEL_NOT_OBSERVED,"이번 조회에서 기존 취소 거래가 관찰되지 않았습니다. 재전송 근거가 아닙니다.",observation); }
        if (matches.size()!=1) { return decision(Verdict.RESPONSE_MISMATCH,"동일 취소 거래 키가 중복됩니다.",observation); }
        TossCancelResponse.Cancel cancel=matches.getFirst();
        if (!"DONE".equals(cancel.cancelStatus()) || !equal(local.cancelAmount(),cancel.cancelAmount())
                || cancel.canceledAt()==null || !("CANCELED".equals(payment.status()) || "PARTIAL_CANCELED".equals(payment.status()))
                || payment.balanceAmount()==null || payment.balanceAmount().signum()<0
                || payment.balanceAmount().compareTo(payment.totalAmount())>0) {
            return decision(Verdict.RESPONSE_MISMATCH,"기존 취소 거래의 상태·금액 또는 원결제 상태가 일치하지 않습니다.",observation);
        }
        return decision(Verdict.EXTERNAL_CANCEL_CONFIRMED,"기존 거래 키와 금액에 일치하는 외부 취소 완료를 확인했습니다. 서버 정합성 수정은 별도입니다.",observation);
    }
    /** 신규 결과 생성에도 금융 수정/재시도 권한을 추가하지 않는다. */
    private Decision decision(Verdict verdict,String reason,Observation observation) { return new Decision(verdict,reason,observation); }
    /** BigDecimal 스케일 차이를 금액 불일치로 취급하지 않는다. */
    private static boolean equal(BigDecimal a,BigDecimal b) { return a!=null && b!=null && a.compareTo(b)==0; }
    /** 거래 원문 키 대신 대조 가능한 단방향 해시만 공개·보관한다. */
    static String hash(String key) {
        if (key==null || key.isBlank()) { return null; }
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
