package kr.co.teambrain.marvelrun.admin.event.query.support;

import kr.co.teambrain.marvelrun.admin.event.query.dto.report.RegistrationDeliveryReportModels.*;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** 불명확 신청의 연락 전 확인사항과 자원·금융 이력을 작성한다. 명단 판정이나 확정 구성은 변경하지 않는다. */
@Component
public class RegistrationDeliveryUnclearReviewFormatter {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 신청에 귀속된 기록을 KST 시간순으로 합치고 시각 없는 현재 상태는 마지막에 표시한다. */
    public UnclearReview formatUnclearRegistrationReview(Classification classification, HistoryResult history,
            List<ReviewEvent> reservationEvents, List<PaymentFact> payments, List<RefundFact> refunds) {
        // 결제 승인만 UTC를 변환하며 환불 요청·완료와 예약 이력은 저장된 KST를 사용한다.
        List<ReviewEvent> events = new ArrayList<>(reservationEvents);
        for (PaymentFact payment : payments) {
            String label = paymentPurpose(payment.purpose()) + " / 신청 귀속 " + amount(payment.amount());
            if ("COMPLETED".equals(payment.processStatus())) {
                events.add(new ReviewEvent(payment.approvedUtc() == null ? null : payment.approvedUtc().plusHours(9),
                        label + " / 결제 완료" + (payment.approvedUtc() == null ? " (승인 시각 확인 필요)" : "")));
            } else {
                events.add(new ReviewEvent(null,label + " / 현재 결제 상태: " + paymentState(payment.processStatus())));
            }
        }
        for (RefundFact refund : refunds) {
            String label = refundPurpose(refund.purpose()) + " / 신청 귀속 " + amount(refund.amount());
            events.add(new ReviewEvent(refund.requestedKst(),label + " / 환불 요청"
                    + " (현재 상태: " + refundState(refund.status()) + ")"));
            if ("DONE".equals(refund.status())) {
                events.add(new ReviewEvent(refund.canceledKst(),label + " / 환불 완료"));
            }
        }

        // 동일 시각은 기존 입력 순서를 유지하며 시간 근접성만으로 수정·결제의 인과관계를 만들지 않는다.
        events.sort(Comparator.comparing(ReviewEvent::occurredKst,Comparator.nullsLast(Comparator.naturalOrder())));
        String timeline = events.stream().map(event ->
                (event.occurredKst() == null ? "[시각 확인 불가]" : event.occurredKst().format(TIME))
                        + " " + event.description()).collect(Collectors.joining("\n"));
        return new UnclearReview(buildReviewInstructions(classification,history,refunds),timeline);
    }

    /** 내부 정산 확인과 참가자에게 문의할 항목을 구분하여 중복 없이 안내한다. */
    private String buildReviewInstructions(Classification classification, HistoryResult history, List<RefundFact> refunds) {
        // 상태별로 실제 필요한 확인 절차를 안내하며 과거 실패 결제만으로 추가 조치를 요구하지 않는다.
        Set<String> instructions = new LinkedHashSet<>();
        for (String reason : classification.reasons()) {
            switch (reason) {
                case "추가결제 필요" -> {
                    instructions.add("[내부 확인] 추가결제 필요 금액과 처리 여부 확인");
                    instructions.add("[참가자 확인] 현재 변경 선택 유지 여부와 추가결제 진행 의사 확인");
                }
                case "부분환불 필요" -> instructions.add("[내부 확인] 차액환불 필요 금액과 처리 여부 확인");
                case "최초 승인일 확인 불가·기간 판정 불가" ->
                        instructions.add("[내부 확인] 최초 참가비 승인 기록과 조회 기간 포함 여부 확인");
                case "결제·환불 처리 중 또는 결과·귀속 미확정", "결제 취소 상태와 참가자 환불 귀속 확인 필요" ->
                        instructions.add("[내부 확인] 내부 금융 기록과 상점관리자 처리 결과 대조 후 정산 여부 확인");
                case "신청 금액과 금융 원장 불일치", "결제 목적 확인 불가" ->
                        instructions.add("[내부 확인] 신청 금액과 참가자별 결제·환불 귀속 확인");
                case "신청자·종목·배송지 정보 확인 필요" -> {
                    instructions.add("[내부 확인] 연락처·신청자·종목·배송지의 누락 항목 확인");
                    instructions.add("[참가자 확인] 연락 가능한 경우 누락된 배송 정보 확인");
                }
                default -> instructions.add("[내부 확인] " + reason);
            }
        }

        // 부분 복원된 값은 유지하고 확인할 수 없는 과거 항목만 추가 확인 대상으로 안내한다.
        if (!"확인 가능".equals(history.result())) {
            instructions.add("[내부 확인] 최근 확정 정보의 판별 사유와 변경·금융 이력 대조: " + history.reason());
            if (history.previous().category().contains("판별 불가")) {
                instructions.add("[참가자 확인] 기존 참가 종목 확인");
            }
            if (history.previous().souvenirs().contains("판별 불가")) {
                instructions.add("[참가자 확인] 기존 기념품 선택 확인");
            }
            if (history.previous().sizes().contains("판별 불가")) {
                instructions.add("[참가자 확인] 기존 기념품 사이즈 확인");
            }
        }
        if (refunds.stream().anyMatch(refund -> refund.amount() == null)) {
            instructions.add("[내부 확인] 단체 환불의 참가자별 귀속 금액 확인");
        }
        instructions.add("[안내] 연락 결과만으로 결제·환불 완료 또는 배송 확정을 처리하지 마세요.");
        return String.join("\n",instructions);
    }

    /** 개인 귀속이 확인되지 않은 금액은 단체 전체 금액으로 대체하지 않는다. */
    private String amount(BigDecimal amount) {
        return amount == null ? "금액 확인 불가" : amount.stripTrailingZeros().toPlainString() + "원";
    }

    /** 참가자별 결제 목적을 한국어로 표시한다. */
    private String paymentPurpose(String purpose) {
        if (purpose == null) { return "결제 목적 확인 불가"; }
        return switch (purpose) {
            case "REGISTRATION_TRY" -> "최초 참가비";
            case "ADDITIONAL_PAYMENT" -> "추가결제";
            case "MIXED_PAYMENT" -> "혼합 결제";
            default -> "기타 결제";
        };
    }

    /** 현재 결제 상태는 과거 상태 전환 시각을 추정하지 않고 별도로 표시한다. */
    private String paymentState(String state) {
        if (state == null) { return "확인 불가"; }
        return switch (state) {
            case "READY" -> "결제 대기";
            case "CONFIRMING" -> "승인 처리 중";
            case "UNKNOWN" -> "결과 미확정";
            case "FAILED" -> "결제 실패";
            case "INVALIDATED" -> "주문 무효화 (승인 전)";
            default -> "기타 상태 (" + state + ")";
        };
    }

    /** 환불 요청의 업무 목적을 표시한다. */
    private String refundPurpose(String purpose) {
        if (purpose == null) { return "환불 목적 확인 불가"; }
        return switch (purpose) {
            case "REGISTRATION_CANCELLATION" -> "참가 취소 환불";
            case "PRICE_ADJUSTMENT" -> "차액환불";
            case "ADMIN_ADJUSTMENT" -> "관리자 조정 환불";
            case "EVENT_POLICY" -> "대회 정책 환불";
            default -> "기타 환불";
        };
    }

    /** 환불 현재 상태와 완료 사실을 혼동하지 않도록 한국어로 표시한다. */
    private String refundState(String state) {
        if (state == null) { return "확인 불가"; }
        return switch (state) {
            case "DONE" -> "완료";
            case "PROCESSING" -> "처리 중";
            case "UNKNOWN" -> "결과 미확정";
            case "FAILED" -> "실패";
            default -> "기타 상태 (" + state + ")";
        };
    }
}
