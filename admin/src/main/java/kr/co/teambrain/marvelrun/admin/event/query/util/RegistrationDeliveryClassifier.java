package kr.co.teambrain.marvelrun.admin.event.query.util;

import kr.co.teambrain.marvelrun.admin.event.query.dto.report.RegistrationDeliveryReportModels.*;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 신청에 귀속된 현재 증거로 배송 명단의 제외·정상·불명확을 판정한다. 조회와 파일 작성은 하지 않는다. */
@Component
public class RegistrationDeliveryClassifier {
    private static final Set<String> ELIGIBLE = Set.of("CONFIRMED", "ADDITIONAL_PAYMENT_REQUIRED", "PARTIAL_REFUND_REQUIRED");

    /** 제외 조건을 우선 적용하고 미확정 금융·예약·기본 정보의 사유를 누적한다. */
    public Classification classifyRegistrationForDelivery(Candidate row, List<PaymentFact> payments,
            List<ReservationFact> reservations, LocalDateTime startUtc, LocalDateTime endUtc) {
        // 참가 대상이 아닌 신청은 금융 또는 이력 판정을 요구하지 않는다.
        if (row.deleted() || row.status() == null || !ELIGIBLE.contains(row.status())) {
            return new Classification(true, List.of());
        }
        BigDecimal collected = payments.stream().filter(p -> "COMPLETED".equals(p.processStatus()))
                .map(PaymentFact::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal returned = payments.stream().map(PaymentFact::refundedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean unresolved = payments.stream().anyMatch(p -> p.processStatus() == null || p.refundUnsettled() || p.refundAttributionMissing()
                || "UNKNOWN".equals(p.processStatus()) || "CONFIRMING".equals(p.processStatus()));
        if (collected.signum() > 0 && returned.compareTo(collected) == 0 && !unresolved) {
            return new Classification(true, List.of());
        }
        if (row.firstApprovedUtc() != null && (row.firstApprovedUtc().isBefore(startUtc)
                || !row.firstApprovedUtc().isBefore(endUtc))) {
            return new Classification(true, List.of());
        }

        // 미정산 상태와 기간 판정 불가를 서로 다른 사유로 남긴다.
        List<String> reasons = new ArrayList<>();
        if ("ADDITIONAL_PAYMENT_REQUIRED".equals(row.status())) { reasons.add("추가결제 필요"); }
        if ("PARTIAL_REFUND_REQUIRED".equals(row.status())) { reasons.add("부분환불 필요"); }
        if (row.firstApprovedUtc() == null) { reasons.add("최초 승인일 확인 불가·기간 판정 불가"); }
        if (unresolved) { reasons.add("결제·환불 처리 중 또는 결과·귀속 미확정"); }
        if (payments.stream().anyMatch(p -> p.purpose() == null)) { reasons.add("결제 목적 확인 불가"); }
        if (row.contractAmount() == null || row.paidAmount() == null || collected.subtract(returned).signum() < 0
                || row.paidAmount() != null && collected.subtract(returned).compareTo(row.paidAmount()) != 0
                || "CONFIRMED".equals(row.status()) && row.contractAmount() != null && row.paidAmount() != null
                    && row.contractAmount().compareTo(row.paidAmount()) != 0) {
            reasons.add("신청 금액과 금융 원장 불일치");
        }
        // 과거 실패 주문 자체는 불명확 사유가 아니며 정상 외부결제의 PG 전용 값도 요구하지 않는다.
        if (payments.stream().anyMatch(p -> "COMPLETED".equals(p.processStatus())
                && "CANCELED".equals(p.tossStatus()) && p.refundedAmount().compareTo(p.amount()) < 0)) {
            reasons.add("결제 취소 상태와 참가자 환불 귀속 확인 필요");
        }
        if (reservations.size() != 1 || reservations.stream().anyMatch(r -> !"CONSUMED".equals(r.status())
                || r.items().isEmpty() || r.items().stream().anyMatch(i -> i.quantity() <= 0)
                || r.items().stream().map(CapacityItem::capacityId).distinct().count() != r.items().size())) {
            reasons.add("예약 상태·개수·확보 상세 불일치");
        }
        if (blank(row.name()) || blank(row.birth()) || blank(row.phone()) || blank(row.categoryName())
                || blank(row.address()) || row.registrationAt() == null || row.organizationId() != null && blank(row.groupName())) {
            reasons.add("신청자·종목·배송지 정보 확인 필요");
        }
        return new Classification(false, List.copyOf(reasons));
    }

    /** 필수 출력값이 비어 있는지 확인한다. 상세주소는 비어 있을 수 있다. */
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
