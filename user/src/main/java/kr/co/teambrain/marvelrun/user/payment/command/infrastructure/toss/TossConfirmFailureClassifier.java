package kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss;

import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.exception.TossPaymentApiException;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Toss 승인 오류 중 명확한 결제 거절로 처리할 수 있는 응답을 구분한다.
 *
 * 확인한 HTTP 상태와 오류 코드 조합만 확정 실패로 분류한다.
 * 처리 중, 중복 처리, 일시적 오류, 서버 오류 및 미등록 코드는
 * 승인 결과 확인이 필요한 대상으로 남긴다.
 */
@Component
public class TossConfirmFailureClassifier {

    // 토스페이먼츠 공식 문서를 기준으로 명확한 결제 거절(400) 코드 Set 추가 (2-E)
    private static final Set<String> DEFINITE_FAILURE_400_CODES = Set.of(
            "INVALID_REJECT_CARD",            // 한도초과 등 거절
            "INVALID_STOPPED_CARD",           // 정지된 카드
            "INVALID_CARD_LOST_OR_STOLEN",    // 분실/도난 카드
            "EXCEED_MAX_AMOUNT",              // 결제 한도 초과
            "EXCEED_MAX_AUTH_COUNT",          // 인증 횟수 초과
            "INVALID_PASSWORD",               // 비밀번호 오류
            "NOT_SUPPORTED_INSTALLMENT_PLAN", // 할부 지원 안 함
            "INVALID_CARD_NUMBER",            // 카드번호 오류
            "INVALID_CARD_EXPIRATION",        // 유효기간 오류
            "PAY_PROCESS_CANCELED",           // 결제 진행 중 취소
            "PAY_PROCESS_ABORTED",            // 결제 진행 중 승인 실패
            "REJECT_TOSSPAY_INVALID_ACCOUNT", // 토스페이 유효하지 않은 계좌
            "NOT_FOUND_PAYMENT_SESSION"       // 결제 세션 만료
    );

    // 토스페이먼츠 공식 문서를 기준으로 명확한 결제 거절(403) 코드 Set 추가 (2-E)
    private static final Set<String> DEFINITE_FAILURE_403_CODES = Set.of(
            "REJECT_CARD_PAYMENT",            // 카드 결제 거절
            "REJECT_CARD_COMPANY",            // 카드사 거절
            "REJECT_ACCOUNT_PAYMENT"          // 계좌 결제 거절
    );

    /**
     * 해당 승인 응답을 명확한 결제 거절로 분류할 수 있는지 판단한다.
     *
     * 외부 응답의 오류 코드가 누락될 수 있으므로
     * 안전하게 Set.contains()를 사용한다.
     */
    public boolean isDefiniteFailure(TossPaymentApiException exception) {
        int httpStatus = exception.getHttpStatus();
        String errorCode = exception.getTossErrorCode();

        if (errorCode == null) {
            return false;
        }

        // 6개 코드 하드코딩
        // if (httpStatus == 400) {
        //     return "INVALID_REJECT_CARD".equals(errorCode)
        //             || "INVALID_STOPPED_CARD".equals(errorCode)
        //             || "INVALID_CARD_LOST_OR_STOLEN".equals(errorCode);
        // }
        //
        // if (httpStatus == 403) {
        //     return "REJECT_CARD_PAYMENT".equals(errorCode)
        //             || "REJECT_CARD_COMPANY".equals(errorCode)
        //             || "REJECT_ACCOUNT_PAYMENT".equals(errorCode);
        // }

        // 확장된 Allowlist 적용 (2-E)
        if (httpStatus == 400) {
            return DEFINITE_FAILURE_400_CODES.contains(errorCode);
        }

        if (httpStatus == 403) {
            return DEFINITE_FAILURE_403_CODES.contains(errorCode);
        }

        /*
         * ALREADY_PROCESSED_PAYMENT(이미 처리된 결제), PROVIDER_ERROR(일시적 오류) 등은
         * false를 반환하여 UNKNOWN 상태로 보존되도록 한다.
         */
        return false;
    }
}