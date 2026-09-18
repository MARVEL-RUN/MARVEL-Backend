package kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss;

import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.exception.TossPaymentApiException;
import org.springframework.stereotype.Component;

/**
 * Toss 승인 오류 중 명확한 결제 거절로 처리할 수 있는 응답을 구분한다.
 *
 * 확인한 HTTP 상태와 오류 코드 조합만 확정 실패로 분류한다.
 * 처리 중, 중복 처리, 일시적 오류, 서버 오류 및 미등록 코드는
 * 승인 결과 확인이 필요한 대상으로 남긴다.
 */
@Component
public class TossConfirmFailureClassifier {

    /**
     * 해당 승인 응답을 명확한 결제 거절로 분류할 수 있는지 판단한다.
     *
     * 외부 응답의 오류 코드가 누락될 수 있으므로
     * 문자열 상수의 equals를 사용한다.
     */
    public boolean isDefiniteFailure(TossPaymentApiException exception) {
        int httpStatus = exception.getHttpStatus();
        String errorCode = exception.getTossErrorCode();

        if (httpStatus == 400) {
            return "INVALID_REJECT_CARD".equals(errorCode)
                    || "INVALID_STOPPED_CARD".equals(errorCode)
                    || "INVALID_CARD_LOST_OR_STOLEN".equals(errorCode);
        }

        if (httpStatus == 403) {
            return "REJECT_CARD_PAYMENT".equals(errorCode)
                    || "REJECT_CARD_COMPANY".equals(errorCode)
                    || "REJECT_ACCOUNT_PAYMENT".equals(errorCode);
        }

        return false;
    }
}