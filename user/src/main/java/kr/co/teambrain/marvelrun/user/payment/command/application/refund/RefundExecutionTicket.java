package kr.co.teambrain.marvelrun.user.payment.command.application.refund;

import java.math.BigDecimal;
import java.util.List;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.refund.TossCancelAttempt;

/** 시작 기록을 커밋한 환불의 식별값과 변경 불가능한 귀속을 전달한다. 외부 API 입력이 아니다. */
public record RefundExecutionTicket(String eventId, String organizationId,
                                    String paymentId, String correlationId, TossCancelAttempt attempt, List<Share> shares) {
    /** 시작 이후 원본 목록이 바뀌어도 결과 적용 기준을 유지한다. */
    public RefundExecutionTicket { shares = List.copyOf(shares); }

    /** 취소 귀속·원결제 귀속·신청·금액의 연결을 고정한다. */
    public record Share(String cancelAllocationId, String originalAllocationId,
                        String registrationId, BigDecimal amount) { }
}