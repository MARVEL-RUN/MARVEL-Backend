package kr.co.teambrain.marvelrun.admin.event.query.support;

import com.fasterxml.jackson.databind.json.JsonMapper;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.RegistrationDeliveryReportModels.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.json_object.ReservationHistoryEntry;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 실제 history 형식과 현재 자원 연결로 복원 가능·불가능의 경계를 검증한다. */
class RegistrationReservationHistoryResolverTest {
    private final RegistrationReservationHistoryResolver resolver=new RegistrationReservationHistoryResolver(JsonMapper.builder().findAndAddModules().build());
    private final LocalDateTime time=LocalDateTime.of(2026,10,1,12,0);

    /** 과거 확정 로그가 구형이어도 첫 신규 수정의 확정 상태 before를 실제 근거로 사용한다. */
    @Test
    void firstEnrichedModificationPreservesPreviouslyConfirmedSelection() {
        ReservationHistoryEntry.Snapshot before = new ReservationHistoryEntry.Snapshot("a", "A",
                List.of(new ReservationHistoryEntry.Selection("s", "티셔츠", "FREE")),
                BigDecimal.TEN, BigDecimal.TEN, "CONFIRMED");
        ReservationHistoryEntry.Snapshot after = new ReservationHistoryEntry.Snapshot("b", "B",
                List.of(new ReservationHistoryEntry.Selection("s", "티셔츠", "L")),
                new BigDecimal("20"), BigDecimal.TEN, "ADDITIONAL_PAYMENT_REQUIRED");
        List<ReservationHistoryEntry> entries = new ArrayList<>(initial());
        entries.add(new ReservationHistoryEntry(ReservationHistoryEntry.Action.MODIFY, 1, time.plusMinutes(2),
                ReservationStatus.CONSUMED, null, "수정", List.of(),
                new ReservationHistoryEntry.Detail(1, "change", "USER", "MODIFY", before, after,
                        false, List.of(), null, List.of())));
        assertThat(resolve(entries, Map.of()).previous()).isEqualTo(new Selection("A", "티셔츠", "FREE"));
    }

    /** 신규 스냅샷은 현재 재고 참조 없이 FREE를 보존하고 반복 미정산 수정 전 마지막 확정을 선택한다. */
    @Test
    void detailedSnapshotKeepsLatestConfirmedSelectionAcrossRepeatedChanges() throws Exception {
        ReservationHistoryEntry.Snapshot confirmed = new ReservationHistoryEntry.Snapshot("a", "확정 종목",
                List.of(new ReservationHistoryEntry.Selection("s", "당시 기념품", "FREE")),
                BigDecimal.TEN, BigDecimal.TEN, "CONFIRMED");
        ReservationHistoryEntry.Snapshot pending = new ReservationHistoryEntry.Snapshot("b", "변경 종목",
                List.of(new ReservationHistoryEntry.Selection("s", "당시 기념품", "L")),
                new BigDecimal("20"), BigDecimal.TEN, "ADDITIONAL_PAYMENT_REQUIRED");
        List<ReservationHistoryEntry> entries = List.of(
                new ReservationHistoryEntry(ReservationHistoryEntry.Action.MODIFY, 1, time, ReservationStatus.CONSUMED,
                        null, "동액 확정", List.of(), new ReservationHistoryEntry.Detail(1, "change1", "USER",
                                "NO_BALANCE_CONFIRMED", null, confirmed, true, List.of(), null, List.of())),
                new ReservationHistoryEntry(ReservationHistoryEntry.Action.MODIFY, 1, time.plusSeconds(1), ReservationStatus.CONSUMED,
                        null, "변경", List.of(), new ReservationHistoryEntry.Detail(1, "change2", "USER",
                                "MODIFY", confirmed, pending, false, List.of(), null, List.of())));
        String json = JsonMapper.builder().findAndAddModules().build().writeValueAsString(entries);
        RegistrationReservationHistoryResolver.HistoryInput input = resolver.readHistory(json);
        assertThat(input.error()).isEmpty();
        HistoryResult result = resolver.resolveRegistrationHistory(input, Map.of(), List.of());
        assertThat(result.previous()).isEqualTo(new Selection("확정 종목", "당시 기념품", "FREE"));
        assertThat(result.text()).contains("변경 종목", "정산 확정").doesNotContain("change1", "change2");
        assertThat(resolver.describeUnclearReservationHistory(input, Map.of(), List.of()).getLast().description())
                .contains("FREE", "L");
    }

    /** 선택 사이즈가 실제로 기록되지 않은 상세도 현재값으로 추정하지 않는다. */
    @Test
    void detailedSnapshotDoesNotInventMissingSize() {
        ReservationHistoryEntry.Snapshot missing = new ReservationHistoryEntry.Snapshot("a", "A",
                List.of(new ReservationHistoryEntry.Selection("s", "기념품", null)), BigDecimal.ZERO, BigDecimal.ZERO, "CONFIRMED");
        ReservationHistoryEntry entry = new ReservationHistoryEntry(ReservationHistoryEntry.Action.ZERO_AMOUNT_CONFIRMED,
                1, time, ReservationStatus.CONSUMED, null, "0원", List.of(),
                new ReservationHistoryEntry.Detail(1, "x", "USER", "ZERO_AMOUNT_CONFIRMED", null, missing, true, List.of(), null, List.of()));
        HistoryResult result = resolver.resolveRegistrationHistory(
                new RegistrationReservationHistoryResolver.HistoryInput(List.of(entry), ""), Map.of(), List.of());
        assertThat(result.previous().sizes()).isEqualTo("판별 불가");
        assertThat(result.result()).isEqualTo("일부 판별 불가");
    }

    /** 단일 미정산 수정은 명시적으로 확정된 이전 자원 구성과 현재 이력을 구분한다. */
    @Test
    void restoresPreviousCompositionAndDoesNotDoubleCountSouvenirTotals() {
        List<ReservationHistoryEntry> entries=new ArrayList<>(initial());
        entries.add(entry(ReservationHistoryEntry.Action.MODIFY,3,List.of(item("catB"),item("shirtL"))));
        HistoryResult result=resolve(entries,capacities());
        assertThat(result.result()).isEqualTo("확인 가능");
        assertThat(result.previous()).isEqualTo(new Selection("A","티셔츠","S"));
        assertThat(result.text()).contains("수정: B / 티셔츠 / L").doesNotContain("catB");
    }

    /** 반복 수정 사이의 동일 금액 확정을 알 수 없으면 A나 B를 임의 확정하지 않는다. */
    @Test
    void repeatedModificationsPreserveTimelineButReportAmbiguousSettlementBoundary() {
        List<ReservationHistoryEntry> entries=new ArrayList<>(initial());
        entries.add(entry(ReservationHistoryEntry.Action.MODIFY,3,List.of(item("catB"),item("shirtL"))));
        entries.add(entry(ReservationHistoryEntry.Action.MODIFY,4,List.of(item("catA"),item("shirtL"))));
        HistoryResult result=resolve(entries,capacities());
        assertThat(result.result()).isEqualTo("판별 불가");
        assertThat(result.reason()).isEqualTo("최근 확정 시점 구분 불가");
        assertThat(result.text()).contains("수정: B","수정: A");
    }

    /** 전체 재고만 기록된 사이즈와 누락된 자원을 신규 정보로 채우지 않는다. */
    @Test
    void reportsPartialUnknownSizeAndMissingResource() {
        List<ReservationHistoryEntry> entries=List.of(entry(ReservationHistoryEntry.Action.HOLD,0,List.of(item("catA"),item("shirtTotal"))),
                entry(ReservationHistoryEntry.Action.PAYMENT_CONFIRMED,1,List.of()));
        HistoryResult partial=resolve(entries,capacities());
        assertThat(partial.result()).isEqualTo("일부 판별 불가");
        assertThat(partial.previous().category()).isEqualTo("A");
        assertThat(partial.previous().sizes()).isEqualTo("판별 불가");
        assertThat(resolve(initial(),Map.of()).result()).isEqualTo("판별 불가");
    }

    /** 최근 추가결제·환불 완료의 확정 구성을 증명하지 못하면 최초 결제 구성을 대신 내보내지 않는다. */
    @Test
    void doesNotTreatInitialApprovalAsLatestAfterLaterFinancialSettlement() {
        List<PaymentFact> facts=new ArrayList<>(payments());
        facts.add(new PaymentFact("registration","additional","ADDITIONAL_PAYMENT","COMPLETED",time.plusDays(1).minusHours(9),
                new BigDecimal("10000"),BigDecimal.ZERO,false,false,"DONE"));
        HistoryResult result=resolver.resolveRegistrationHistory(new RegistrationReservationHistoryResolver.HistoryInput(initial(),""),capacities(),facts);
        assertThat(result.reason()).isEqualTo("최근 확정 시점 구분 불가");
    }

    /** 무결제 동일 금액 수정까지 현재 참가 확정으로 검증된 경우 최신 구성을 사용한다. */
    @Test
    void usesLatestCompositionWhenCurrentSettlementIsVerified() {
        List<ReservationHistoryEntry> entries=new ArrayList<>(initial());
        entries.add(entry(ReservationHistoryEntry.Action.MODIFY,3,List.of(item("catB"),item("shirtL"))));
        HistoryResult result=resolver.resolveRegistrationHistory(new RegistrationReservationHistoryResolver.HistoryInput(entries,""),capacities(),payments(),true);
        assertThat(result.previous()).isEqualTo(new Selection("B","티셔츠","L"));
    }

    /** 형식 오류와 없는 이력을 데이터 판별 실패로 남긴다. */
    @Test
    void distinguishesMalformedAndMissingHistory() {
        assertThat(resolver.readHistory("not-json").error()).contains("JSON");
        assertThat(resolver.readHistory("[]").error()).contains("누락");
        assertThat(resolver.readHistory("[null]").error()).contains("필수 정보");
    }

    /** 자원 이력과 금융 원장의 같은 최초 승인을 중복 표시하지 않는다. */
    @Test
    void reviewEventsKeepCompositionAndAvoidDuplicateApproval() {
        RegistrationReservationHistoryResolver.HistoryInput input =
                new RegistrationReservationHistoryResolver.HistoryInput(initial(),"");
        List<ReviewEvent> events=resolver.describeUnclearReservationHistory(input,capacities(),payments());
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().description()).contains("최초 확보","A / 티셔츠 / S").doesNotContain("payment","catA");
        assertThat(resolver.describeUnclearReservationHistory(resolver.readHistory("not-json"),capacities(),payments())
                .getFirst().occurredKst()).isNull();
    }

    /** 승인 UTC만 보정하고 환불 KST는 유지하며, 미확정 현재 상태를 완료 이력으로 만들지 않는다. */
    @Test
    void reviewCombinesFinancialTimelineWithoutGuessingSettlement() {
        RegistrationDeliveryUnclearReviewFormatter formatter=new RegistrationDeliveryUnclearReviewFormatter();
        HistoryResult history=new HistoryResult(new Selection("A","티셔츠","판별 불가"),
                "일부 판별 불가","과거 선택 사이즈 기록 부족 또는 복수 후보","");
        List<PaymentFact> facts=new ArrayList<>(payments());
        facts.add(new PaymentFact("registration","additional","ADDITIONAL_PAYMENT","UNKNOWN",null,
                new BigDecimal("10000"),BigDecimal.ZERO,false,false,null));
        List<RefundFact> refunds=List.of(
                new RefundFact("registration","done","PRICE_ADJUSTMENT","DONE",new BigDecimal("2000"),
                        time.plusMinutes(1),time.plusMinutes(2)),
                new RefundFact("registration","unknown","PRICE_ADJUSTMENT","UNKNOWN",null,time.plusMinutes(3),null));
        UnclearReview result=formatter.formatUnclearRegistrationReview(
                new Classification(false,List.of("추가결제 필요")),history,
                List.of(new ReviewEvent(time.minusMinutes(1),"수정: B / 티셔츠 / L")),facts,refunds);

        // 확인 불가 항목만 연락 대상으로 안내하고 단체 전체 금액이나 내부 ID를 노출하지 않는다.
        assertThat(result.instructions()).contains("[내부 확인]","[참가자 확인] 기존 기념품 사이즈 확인","단체 환불")
                .doesNotContain("[참가자 확인] 기존 참가 종목 확인");
        assertThat(result.timeline()).contains("2026-10-01 12:00:00 최초 참가비",
                "2026-10-01 12:02:00 차액환불 / 신청 귀속 2000원 / 환불 완료",
                "금액 확인 불가 / 환불 요청 (현재 상태: 결과 미확정)",
                "[시각 확인 불가] 추가결제").doesNotContain("registration","additional","21:02");
        assertThat(result.timeline().indexOf("수정: B")).isLessThan(result.timeline().indexOf("최초 참가비"));
        assertThat(result.timeline().indexOf("최초 참가비")).isLessThan(result.timeline().indexOf("환불 완료"));
    }

    /** 승인·환불 완료 시각이 없으면 요청 시각으로 대체하지 않는다. */
    @Test
    void reviewKeepsMissingCompletionTimesUnknown() {
        PaymentFact missing=new PaymentFact("r","p","REGISTRATION_TRY","COMPLETED",null,
                BigDecimal.ZERO,BigDecimal.ZERO,false,false,null);
        UnclearReview review=new RegistrationDeliveryUnclearReviewFormatter().formatUnclearRegistrationReview(
                new Classification(false,List.of("최초 승인일 확인 불가·기간 판정 불가")),
                new HistoryResult(new Selection("A","",""),"확인 가능","",""),List.of(),List.of(missing),
                List.of(new RefundFact("r","c","PRICE_ADJUSTMENT","DONE",BigDecimal.ONE,time,null)));
        assertThat(review.timeline()).contains("[시각 확인 불가] 최초 참가비",
                "[시각 확인 불가] 차액환불 / 신청 귀속 1원 / 환불 완료");
        assertThat(review.timeline()).contains("2026-10-01 12:00:00 차액환불 / 신청 귀속 1원 / 환불 요청");
    }

    /** 테스트 공통 이력에 실제 확정 결제 증거를 연결한다. */
    private HistoryResult resolve(List<ReservationHistoryEntry> entries,Map<String,CapacityInfo> capacities) {
        return resolver.resolveRegistrationHistory(new RegistrationReservationHistoryResolver.HistoryInput(entries,""),capacities,payments());
    }

    /** 최초 확보에 전체 재고와 사이즈 재고가 같이 기록되는 구성을 준비한다. */
    private List<ReservationHistoryEntry> initial() {
        return List.of(entry(ReservationHistoryEntry.Action.HOLD,0,List.of(item("catA"),item("shirtTotal"),item("shirtS"))),
                entry(ReservationHistoryEntry.Action.PAYMENT_CONFIRMED,1,List.of()));
    }

    /** 배열 순서와 업무 시각이 일치하는 이력 한 건이다. */
    private ReservationHistoryEntry entry(ReservationHistoryEntry.Action action,int minutes,List<ReservationHistoryEntry.Item> items) {
        return new ReservationHistoryEntry(action,1,time.plusMinutes(minutes),ReservationStatus.CONSUMED,
                action==ReservationHistoryEntry.Action.PAYMENT_CONFIRMED ? "payment" : null,"테스트",items);
    }

    /** 한 명의 자원 수량을 기록한다. */
    private ReservationHistoryEntry.Item item(String id) { return new ReservationHistoryEntry.Item(id,1); }

    /** 최초 결제 귀속을 준비한다. */
    private List<PaymentFact> payments() {
        return List.of(new PaymentFact("registration","payment","REGISTRATION_TRY","COMPLETED",time.minusHours(9),
                new BigDecimal("40000"),BigDecimal.ZERO,false,false,"DONE"));
    }

    /** 종목 두 개와 동일 기념품의 전체·사이즈 자원이다. */
    private Map<String,CapacityInfo> capacities() {
        return Map.of("catA",new CapacityInfo("catA","CATEGORY",null,null,"",List.of("a"),List.of("A")),
                "catB",new CapacityInfo("catB","CATEGORY",null,null,"",List.of("b"),List.of("B")),
                "shirtTotal",new CapacityInfo("shirtTotal","SOUVENIR","shirt","티셔츠","",List.of(),List.of()),
                "shirtS",new CapacityInfo("shirtS","SOUVENIR","shirt","티셔츠","S",List.of(),List.of()),
                "shirtL",new CapacityInfo("shirtL","SOUVENIR","shirt","티셔츠","L",List.of(),List.of()));
    }
}
