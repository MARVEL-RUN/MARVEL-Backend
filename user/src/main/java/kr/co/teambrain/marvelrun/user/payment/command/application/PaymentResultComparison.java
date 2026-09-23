package kr.co.teambrain.marvelrun.user.payment.command.application;
/** 로그의 목표 동작 비교값이다. Payment/PaymentCancel의 DB 상태 enum이 아니다. */
public enum PaymentResultComparison {
    SUCCESS, MISMATCH, FAILED, UNVERIFIED
}
