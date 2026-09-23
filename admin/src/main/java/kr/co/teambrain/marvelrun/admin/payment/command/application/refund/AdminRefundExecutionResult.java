package kr.co.teambrain.marvelrun.admin.payment.command.application.refund;
import kr.co.teambrain.marvelrun.admin.payment.command.infrastructure.toss.refund.TossCancelOutcome;
/** 원결제별 실행 결과다. DB 저장 실패를 외부 성공과 구분하며 배치 예외 수집에 사용한다. */
public record AdminRefundExecutionResult(String paymentCancelId, boolean started,
        TossCancelOutcome externalOutcome, boolean outcomeStored, boolean unknownStored, String errorCode) { }
