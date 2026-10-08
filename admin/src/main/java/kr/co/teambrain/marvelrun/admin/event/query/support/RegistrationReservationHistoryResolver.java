package kr.co.teambrain.marvelrun.admin.event.query.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.RegistrationDeliveryReportModels.*;
import kr.co.teambrain.marvelrun.common.json_object.ReservationHistoryEntry;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 예약 이력의 자원 구성을 해석한다. 근거 없는 최근 확정 정보는 추정하지 않는다. */
@Component
public class RegistrationReservationHistoryResolver {
    private final ObjectMapper mapper;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 애플리케이션의 날짜·JSON 설정을 공유한다. */
    public RegistrationReservationHistoryResolver(ObjectMapper mapper) { this.mapper = mapper; }

    /** 형식 오류도 해당 신청의 판별 사유로 전달하는 이력 입력이다. */
    public record HistoryInput(List<ReservationHistoryEntry> entries, String error) { }

    /** 현재 선택 정보의 해석 결과와 데이터 이상 사유다. */
    public record CurrentSelection(Selection selection, List<String> reasons) { }

    /** 기념품 목록을 읽는다. 잘못된 JSON과 빈 목록을 구분하기 위해 실패를 호출부에 전달한다. */
    public List<SouvenirJson> readSouvenirs(String json) throws JsonProcessingException {
        if (json == null || json.isBlank()) { return List.of(); }
        SouvenirJson[] values = mapper.readValue(json, SouvenirJson[].class);
        return values == null ? List.of() : Arrays.asList(values);
    }

    /** 이력 전체의 배열 순서를 보존하고 알려진 JSON 오류만 판별 불가로 변환한다. */
    public HistoryInput readHistory(String json) {
        if (json == null || json.isBlank()) { return new HistoryInput(List.of(),"예약 이력 누락"); }
        try {
            ReservationHistoryEntry[] entries = mapper.readValue(json, ReservationHistoryEntry[].class);
            if (entries == null || entries.length == 0) { return new HistoryInput(List.of(),"예약 이력 누락"); }
            if (Arrays.stream(entries).anyMatch(e -> e == null || e.action() == null || e.occurredAt() == null
                    || e.items() == null || e.items().stream().anyMatch(i -> i == null || i.capacityId() == null || i.quantity() <= 0))) {
                return new HistoryInput(List.of(),"예약 이력 필수 정보 오류");
            }
            return new HistoryInput(List.of(entries), "");
        } catch (JsonProcessingException exception) {
            return new HistoryInput(List.of(),"예약 이력 JSON 해석 불가");
        }
    }

    /** 현재 신청 선택값을 출력하고 확보된 자원과의 일치 여부를 검사한다. */
    public CurrentSelection resolveCurrentSelection(Candidate row, List<SouvenirJson> souvenirs,
            Map<String,String> names, List<ReservationFact> reservations, Map<String,CapacityInfo> capacities) {
        // 출력은 신청의 최신 선택을 사용하고 참조 누락을 숨기지 않는다.
        List<String> reasons = new ArrayList<>();
        List<String> souvenirNames = new ArrayList<>();
        List<String> sizes = new ArrayList<>();
        Map<String,String> expected = new LinkedHashMap<>();
        for (SouvenirJson souvenir : souvenirs) {
            if (souvenir == null || souvenir.souvenirId() == null || expected.containsKey(souvenir.souvenirId())) {
                reasons.add("기념품 선택 정보 누락·중복"); continue;
            }
            expected.put(souvenir.souvenirId(),souvenir.selectedSize());
            String name = names.get(souvenir.souvenirId());
            souvenirNames.add(name == null ? "판별 불가" : name);
            sizes.add(souvenir.selectedSize() == null ? "" : souvenir.selectedSize());
            if (name == null) { reasons.add("기념품 참조 정보 누락"); }
        }

        // 확정 예약이어도 현재 신청과 상세가 다른 경우 메인 명단에서 분리한다.
        if (reservations.size() == 1) {
            Set<String> actualSouvenirs = new HashSet<>();
            int categories = 0;
            int totals = 0;
            for (CapacityItem item : reservations.getFirst().items()) {
                CapacityInfo capacity = capacities.get(item.capacityId());
                if (capacity == null || item.quantity() != 1) { reasons.add("확보 자원 참조·수량 확인 필요"); continue; }
                if ("EVENT_TOTAL".equals(capacity.type())) { totals++; }
                if ("CATEGORY".equals(capacity.type())) {
                    categories++;
                    if (capacity.categoryIds().size() != 1 || !capacity.categoryIds().contains(row.categoryId())) {
                        reasons.add("신청 종목과 확보 종목 불일치");
                    }
                }
                if ("SOUVENIR".equals(capacity.type())) {
                    actualSouvenirs.add(capacity.souvenirId());
                    if (!expected.containsKey(capacity.souvenirId()) || capacity.size() != null && !capacity.size().isBlank()
                            && !Objects.equals(capacity.size(),expected.get(capacity.souvenirId()))) {
                        reasons.add("신청 기념품·사이즈와 확보 상세 불일치");
                    }
                }
            }
            if (totals != 1 || categories != 1 || !actualSouvenirs.equals(expected.keySet())) {
                reasons.add("필수 확보 구성과 신청 선택 불일치");
            }
        }
        return new CurrentSelection(new Selection(row.categoryName(),String.join("\n",souvenirNames),String.join("\n",sizes)),
                reasons.stream().distinct().toList());
    }

    /** 순서대로 기록된 이력을 출력하고 확인 가능한 마지막 확정 구성만 선택한다. */
    public HistoryResult resolveRegistrationHistory(HistoryInput input, Map<String,CapacityInfo> capacities,
            List<PaymentFact> payments) {
        return resolveRegistrationHistory(input,capacities,payments,false);
    }

    /** 현재 금융·예약 정합성까지 확인된 참가 확정 건은 마지막 자원 구성을 확정 정보로 사용한다. */
    public HistoryResult resolveRegistrationHistory(HistoryInput input, Map<String,CapacityInfo> capacities,
            List<PaymentFact> payments, boolean currentConfirmed) {
        if (!input.error().isBlank()) { return unknown(input.error(),input.error()); }

        // HOLD·REHOLD·MODIFY는 구성 전체이며 결제 동작의 빈 items를 기존 구성으로 덮어쓰지 않는다.
        List<ReservationHistoryEntry> entries = input.entries();
        List<CapacityItem> current = List.of();
        List<CapacityItem> confirmed = List.of();
        int currentSequence = -1;
        int changesSinceConfirmed = 0;
        LocalDateTime confirmedAt = null;
        List<String> lines = new ArrayList<>();
        boolean sequenceBroken = false;
        for (ReservationHistoryEntry entry : entries) {
            boolean composition = switch (entry.action()) { case HOLD, REHOLD, MODIFY -> true; default -> false; };
            if (entry.action() == ReservationHistoryEntry.Action.HOLD || entry.action() == ReservationHistoryEntry.Action.REHOLD) {
                currentSequence = entry.holdSequence(); confirmed = List.of(); confirmedAt = null; changesSinceConfirmed = 0;
            } else if (currentSequence != entry.holdSequence()) { sequenceBroken = true; }
            if (composition) {
                current = entry.items().stream().map(i -> new CapacityItem(i.capacityId(),i.quantity())).toList();
                if (entry.action() == ReservationHistoryEntry.Action.MODIFY) { changesSinceConfirmed++; }
            }
            if (entry.action() == ReservationHistoryEntry.Action.PAYMENT_CONFIRMED) {
                boolean matched = payments.stream().anyMatch(p -> Objects.equals(entry.paymentId(),p.paymentId())
                        && "REGISTRATION_TRY".equals(p.purpose()) && "COMPLETED".equals(p.processStatus()));
                if (matched) { confirmed = current; confirmedAt = entry.occurredAt(); changesSinceConfirmed = 0; }
                else { sequenceBroken = true; }
            }
            if (entry.action() == ReservationHistoryEntry.Action.ZERO_AMOUNT_CONFIRMED) {
                confirmed = current; confirmedAt = entry.occurredAt(); changesSinceConfirmed = 0;
            }
            String line = entry.occurredAt().format(TIME) + " " + actionName(entry.action());
            if (composition) {
                HistoryResult selection = interpret(current,capacities);
                line += ": " + describe(selection.previous());
                if (!selection.reason().isBlank()) { line += " [" + selection.reason() + "]"; }
            }
            lines.add(line);
        }
        String text = String.join("\n",lines);
        if (sequenceBroken || confirmed.isEmpty()) { return unknown("확정 구성과 결제·확보 회차 연결 불가",text); }

        // 동일 금액 수정 등을 마친 현재 상태가 검증된 경우 불필요하게 과거 최초 결제로 되돌리지 않는다.
        if (currentConfirmed) {
            HistoryResult latest=interpret(current,capacities);
            return new HistoryResult(latest.previous(),latest.result(),latest.reason(),text);
        }

        // MODIFY는 신청 상태·계약금액을 저장하지 않는다. 여러 수정 사이의 무결제 확정 여부는 추정하지 않는다.
        LocalDateTime anchor = confirmedAt;
        boolean laterFinancialChange = payments.stream().anyMatch(p -> p.refundedAmount().signum() > 0
                || "COMPLETED".equals(p.processStatus()) && "ADDITIONAL_PAYMENT".equals(p.purpose())
                    && (p.approvedUtc() == null || anchor == null || !p.approvedUtc().plusHours(9).isBefore(anchor)));
        if (changesSinceConfirmed > 1 || laterFinancialChange) {
            return unknown("최근 확정 시점 구분 불가",text);
        }
        HistoryResult restored = interpret(confirmed,capacities);
        return new HistoryResult(restored.previous(),restored.result(),restored.reason(),text);
    }

    /** 복원 판정과 별도로 시간순 표시용 이력을 만들고 금융 원장과 중복되는 승인 기록을 구분한다. */
    public List<ReviewEvent> describeUnclearReservationHistory(HistoryInput input,
            Map<String,CapacityInfo> capacities, List<PaymentFact> payments) {
        if (!input.error().isBlank()) { return List.of(new ReviewEvent(null,input.error())); }

        // 당시 구성만 표시하고 현재 선택값으로 과거 기록을 채우지 않는다.
        List<ReviewEvent> events = new ArrayList<>();
        for (ReservationHistoryEntry entry : input.entries()) {
            boolean matchedApproval = entry.action() == ReservationHistoryEntry.Action.PAYMENT_CONFIRMED
                    && payments.stream().anyMatch(p -> Objects.equals(entry.paymentId(),p.paymentId())
                        && "REGISTRATION_TRY".equals(p.purpose())
                        && "COMPLETED".equals(p.processStatus()) && p.approvedUtc() != null);
            if (matchedApproval) { continue; }
            String description = actionName(entry.action());
            if (entry.action() == ReservationHistoryEntry.Action.PAYMENT_CONFIRMED) {
                description = "예약상 결제 확정 기록 (금융 원장 승인 시각과 별도)";
            }
            if (entry.action() == ReservationHistoryEntry.Action.HOLD
                    || entry.action() == ReservationHistoryEntry.Action.REHOLD
                    || entry.action() == ReservationHistoryEntry.Action.MODIFY) {
                HistoryResult selection = interpret(entry.items().stream()
                        .map(item -> new CapacityItem(item.capacityId(),item.quantity())).toList(),capacities);
                description += ": " + describe(selection.previous());
                if (!selection.reason().isBlank()) { description += " [" + selection.reason() + "]"; }
            }
            events.add(new ReviewEvent(entry.occurredAt(),description));
        }
        return List.copyOf(events);
    }

    /** 자원 목록을 종목·기념품·사이즈로 해석하며 부분 판별 결과를 보존한다. */
    private HistoryResult interpret(List<CapacityItem> items, Map<String,CapacityInfo> capacities) {
        List<String> reasons = new ArrayList<>();
        Set<String> categories = new LinkedHashSet<>();
        Map<String,String> souvenirs = new LinkedHashMap<>();
        Map<String,Set<String>> sizes = new LinkedHashMap<>();
        for (CapacityItem item : items) {
            CapacityInfo capacity = capacities.get(item.capacityId());
            if (capacity == null || item.quantity() != 1) { reasons.add("과거 자원 참조·수량 확인 불가"); continue; }
            if ("CATEGORY".equals(capacity.type())) {
                if (capacity.categoryIds().size() == 1 && capacity.categoryNames().size() == 1) {
                    categories.add(capacity.categoryNames().getFirst());
                } else { reasons.add("과거 종목 연결 누락 또는 복수 후보"); }
            }
            if ("SOUVENIR".equals(capacity.type())) {
                if (capacity.souvenirId() == null || capacity.souvenirName() == null) {
                    reasons.add("과거 기념품 참조 확인 불가"); continue;
                }
                souvenirs.put(capacity.souvenirId(),capacity.souvenirName());
                Set<String> values = sizes.computeIfAbsent(capacity.souvenirId(),ignored -> new LinkedHashSet<>());
                if (capacity.size() != null && !capacity.size().isBlank()) { values.add(capacity.size()); }
            }
        }
        if (categories.size() != 1) { reasons.add("과거 종목 판별 불가"); }
        List<String> outputSizes = new ArrayList<>();
        for (String id : souvenirs.keySet()) {
            Set<String> values = sizes.get(id);
            outputSizes.add(values.size() == 1 ? values.iterator().next() : "판별 불가");
            if (values.size() != 1) { reasons.add("과거 선택 사이즈 기록 부족 또는 복수 후보"); }
        }
        // 참조 자체가 빠지면 누락 자원의 종류도 확정할 수 없으므로 전체 구성을 추정하지 않는다.
        if (items.isEmpty() || reasons.contains("과거 자원 참조·수량 확인 불가") || reasons.contains("과거 기념품 참조 확인 불가")) {
            return unknown("과거 자원 구성 누락·참조 확인 불가","");
        }
        Selection result = new Selection(categories.size() == 1 ? categories.iterator().next() : "판별 불가",
                String.join("\n",souvenirs.values()),String.join("\n",outputSizes));
        return new HistoryResult(result,reasons.isEmpty() ? "확인 가능" : "일부 판별 불가",
                String.join(" / ",new LinkedHashSet<>(reasons)),"");
    }

    /** 내부 enum을 관리자용 한국어 동작명으로 바꾼다. */
    private String actionName(ReservationHistoryEntry.Action action) {
        return switch (action) {
            case HOLD -> "최초 확보"; case REHOLD -> "재확보"; case MODIFY -> "수정";
            case PAYMENT_STARTED -> "최초 결제 처리 시작"; case PAYMENT_CONFIRMED -> "최초 결제 확정";
            case PAYMENT_FAILED -> "최초 결제 실패"; case RELEASE -> "확보 반환";
            case ZERO_AMOUNT_CONFIRMED -> "0원 참가 확정";
        };
    }

    /** 내부 ID 없이 해석된 자원 구성만 출력한다. */
    private String describe(Selection selection) {
        return selection.category() + " / " + selection.souvenirs().replace('\n',',') + " / " + selection.sizes().replace('\n',',');
    }

    /** 판별할 근거가 부족한 경우에도 원인과 읽을 수 있는 이력을 유지한다. */
    private HistoryResult unknown(String reason, String text) {
        return new HistoryResult(Selection.unknown(),"판별 불가",reason,text);
    }
}
