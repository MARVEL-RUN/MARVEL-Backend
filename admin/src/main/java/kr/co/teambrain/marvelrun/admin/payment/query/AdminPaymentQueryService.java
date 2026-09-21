package kr.co.teambrain.marvelrun.admin.payment.query;

import kr.co.teambrain.marvelrun.admin.payment.query.AdminPaymentQueryResponse.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 관리자 조회만 수행하며 PG 호출·상태 복구·납부액 보정은 하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AdminPaymentQueryService {
    private final AdminPaymentQueryRepository repository;
    private final AdminPaymentLogMetadata metadata;

    /** 개인 금융 내역이다. 단체원은 단체 금융 API를 사용하도록 대상 구분을 검증한다. */
    public Finance personalPayments(String eventId, String registrationId, int page, int size) {
        guard(page, size);
        Map<String, Object> registration = first(repository.registration(eventId, registrationId), "신청을 찾을 수 없습니다.");
        if (text(registration, "organization_id") != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "단체 구성원은 단체 금융 내역을 조회해 주세요.");
        }
        return new Finance(registrationId, null, text(registration, "name"), null,
                amount(registration, "contract_amount"), text(registration, "status"),
                statuses(List.of(registrationId), false).get(registrationId),
                paymentPage(eventId, registrationId, false, page, size));
    }

    /** 단체 주문 전체를 반환하며 현재 명단에서 제외된 사람의 금융 귀속도 보존한다. */
    public Finance organizationPayments(String eventId, String organizationId, int page, int size) {
        guard(page, size);
        Map<String, Object> org = requireOrganization(eventId, organizationId);
        return new Finance(null, organizationId, text(org, "group_name"), leader(org),
                amount(org, "contract_amount"), null, statuses(List.of(organizationId), true).get(organizationId),
                paymentPage(eventId, organizationId, true, page, size));
    }

    /** 대회 소속이 확인된 주문의 저장 로그를 읽으며 내부 키는 반환하지 않는다. */
    public Page<Log> logs(String eventId, String paymentId, int page, int size) {
        guard(page, size);
        if (!repository.paymentBelongsToEvent(eventId, paymentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "해당 대회의 결제를 찾을 수 없습니다.");
        }
        long total = repository.logCount(paymentId);
        List<Log> result = repository.logs(paymentId, page, size).stream().map(r -> new Log(
                time(r, "created_at"), text(r, "order_id"), text(r, "process_type"), text(r, "source"),
                r.get("http_status") == null ? null : ((Number) r.get("http_status")).intValue(),
                text(r, "error_code"), text(r, "error_message"), metadata.read(r.get("metadata")))).toList();
        return page(result, page, size, total);
    }

    /** 주문 페이지의 전체 하위 금융 데이터를 한 번씩 읽고 메모리에서 묶는다. */
    private Page<Payment> paymentPage(String eventId, String targetId, boolean group, int page, int size) {
        long total = repository.paymentCount(targetId, group);
        List<Map<String, Object>> payments = repository.payments(targetId, group, page, size);
        List<String> ids = payments.stream().map(r -> text(r, "id")).toList();
        Map<String, List<Map<String, Object>>> allocations = group(repository.allocations(eventId, ids), "payment_id");
        List<Map<String, Object>> cancelRows = repository.cancels(ids);
        Map<String, List<Map<String, Object>>> cancels = group(cancelRows, "payment_id");
        Map<String, List<Map<String, Object>>> cancelAllocations = group(repository.cancelAllocations(eventId,
                cancelRows.stream().map(r -> text(r, "id")).toList()), "payment_cancel_id");
        List<Payment> result = new ArrayList<>();
        for (Map<String, Object> p : payments) {
            String paymentId = text(p, "id");
            List<Allocation> shares = allocations.getOrDefault(paymentId, List.of()).stream().map(a -> new Allocation(
                    text(a, "id"), text(a, "registration_id"), text(a, "name"), amount(a, "allocated_amount"),
                    text(a, "allocation_purpose"), excluded(a, group, targetId), a.get("found_registration") == null)).toList();
            List<Cancel> cancelResult = new ArrayList<>();
            for (Map<String, Object> c : cancels.getOrDefault(paymentId, List.of())) {
                List<CancelAllocation> refunded = cancelAllocations.getOrDefault(text(c, "id"), List.of()).stream()
                        .map(a -> new CancelAllocation(text(a, "id"), text(a, "payment_allocation_id"),
                                text(a, "registration_id"), text(a, "name"), amount(a, "allocated_amount"),
                                excluded(a, group, targetId), a.get("found_registration") == null,
                                a.get("found_allocation") == null,
                                a.get("found_allocation") != null && !paymentId.equals(text(a, "original_payment_id")))).toList();
                cancelResult.add(new Cancel(text(c, "id"), text(c, "cancel_type"), text(c, "purpose"),
                        amount(c, "cancel_amount"), text(c, "cancel_reason"), text(c, "status"),
                        time(c, "created_at"), time(c, "requested_at"), time(c, "canceled_at"),
                        text(c, "error_code"), text(c, "error_message"), amount(c, "refundable_amount_after_cancel"),
                        refunded.isEmpty(), refunded));
            }
            result.add(new Payment(paymentId, text(p, "order_id"), text(p, "order_name"), amount(p, "amount"),
                    text(p, "purpose"), text(p, "process_status"), text(p, "toss_status"),
                    text(p, "payment_method"), text(p, "easy_pay_provider"), time(p, "created_at"), time(p, "approved_at"),
                    shares.isEmpty(), shares, cancelResult));
        }
        return page(result, page, size, total);
    }

    /** 삭제·소속 변경·참가자 누락을 현재 명단 제외로 표시한다. 금융 행을 제거하지 않는다. */
    private boolean excluded(Map<String, Object> row, boolean group, String targetId) {
        return row.get("found_registration") == null || bool(row, "is_del")
                || (group && !Objects.equals(targetId, text(row, "organization_id")));
    }

    /** 복수 주문 상태에서 정해진 우선순위의 실제 DB 값을 얻는다. 결제 가능 여부 판정은 아니다. */
    private Map<String, String> statuses(List<String> ids, boolean group) {
        Map<String, String> result = new HashMap<>();
        for (Map<String, Object> row : repository.statuses(ids, group)) {
            result.put(text(row, "target_id"), text(row, "process_status"));
        }
        return result;
    }

    /** 일괄 조회한 금융 행을 부모 ID별로 묶는다. */
    private Map<String, List<Map<String, Object>>> group(List<Map<String, Object>> rows, String key) {
        return rows.stream().collect(Collectors.groupingBy(r -> text(r, key)));
    }

    /** 대회와 단체 소속을 동시에 검증한다. */
    private Map<String, Object> requireOrganization(String eventId, String id) {
        return first(repository.organization(eventId, id), "해당 대회의 단체를 찾을 수 없습니다.");
    }

    /** 고유 식별자로 조회한 대상의 존재를 확인한다. */
    private Map<String, Object> first(List<Map<String, Object>> rows, String message) {
        if (rows.isEmpty()) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
        return rows.get(0);
    }

    /** 기존 관리자 인증을 사용하며 메서드 보안 설정에 관계없이 읽기 전에 권한을 검증한다. */
    private void guard(int page, int size) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AccessDeniedException("관리자 권한이 필요합니다.");
        }
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page는 0 이상, size는 1~100입니다.");
        }
    }

    /** Organization의 대표자 컬럼만 사용한다. */
    private Leader leader(Map<String, Object> r) {
        return new Leader(text(r, "leader_name"), text(r, "leader_birth"), text(r, "leader_ph_num"));
    }

    /** 고정된 컬럼명으로 문자열을 읽는다. nullable 컬럼은 null을 유지한다. */
    private String text(Map<String, Object> r, String key) { return (String) r.get(key); }

    /** 금액 컬럼은 임의 보정 없이 그대로 반환한다. */
    private BigDecimal amount(Map<String, Object> r, String key) { return (BigDecimal) r.get(key); }

    /** MySQL tinyint의 Boolean/Number 매핑을 처리한다. LEFT JOIN 누락은 false이다. */
    private boolean bool(Map<String, Object> r, String key) {
        Object value = r.get(key);
        return value instanceof Boolean flag ? flag : value instanceof Number number && number.intValue() != 0;
    }

    /** nullable JDBC datetime을 API 시각으로 변환한다. */
    private LocalDateTime time(Map<String, Object> r, String key) {
        Object value = r.get(key);
        return value == null ? null : value instanceof LocalDateTime time ? time : ((Timestamp) value).toLocalDateTime();
    }

    /** 요청한 페이지와 전체 건수를 함께 반환한다. */
    private <T> Page<T> page(List<T> content, int page, int size, long total) {
        return new Page<>(content, page, size, total, total / size + (total % size == 0 ? 0 : 1));
    }
}
