package kr.co.teambrain.marvelrun.admin.event.query.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationDeliveryExcelRequest;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.RegistrationDeliveryExcelWriter;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.RegistrationDeliveryReportModels.*;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationDeliveryQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationReservationHistoryResolver;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationDeliveryUnclearReviewFormatter;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationReservationHistoryResolver.*;
import kr.co.teambrain.marvelrun.admin.event.query.support.TemporaryExcelResource;
import kr.co.teambrain.marvelrun.admin.event.query.util.RegistrationDeliveryClassifier;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 한 읽기 스냅샷에서 대상 분류와 불명확 이력 해석을 배치 처리하고 완성된 파일만 반환한다. */
@Service
@RequiredArgsConstructor
public class RegistrationDeliveryExcelService {
    private static final int BATCH_SIZE=100;
    private final RegistrationDeliveryQueryRepository repository;
    private final RegistrationDeliveryClassifier classifier;
    private final RegistrationReservationHistoryResolver resolver;
    private final RegistrationDeliveryExcelWriter writer;
    private final ServerTimeProvider timeProvider;
    private final RegistrationDeliveryUnclearReviewFormatter unclearReviewFormatter;

    /** 생성 중의 결제·수정이 배치에 섞이지 않도록 독립적인 반복 읽기 트랜잭션을 사용한다. */
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ, propagation=Propagation.REQUIRES_NEW)
    public TemporaryExcelResource createRegistrationDeliveryExcel(String eventId, RegistrationDeliveryExcelRequest request) {
        // 입력은 파일 생성과 DB 조회 전에 검증한다.
        validateDeliveryPeriod(eventId,request);
        EventInfo event=repository.findDeliveryEvent(eventId).orElseThrow(() -> new CustomException(ErrorCode.EVENT_NOT_FOUND));
        LocalDateTime now=timeProvider.currentDateTime();
        Path file=null;
        boolean ready=false;
        try {
            file=Files.createTempFile("marvelrun-delivery-", ".xlsx");
            try (RegistrationDeliveryExcelWriter.WorkbookSession workbook=writer.openDeliveryWorkbook(event,request,now)) {
                Candidate cursor=null;
                while (true) {
                    List<Candidate> candidates=repository.findDeliveryCandidates(eventId,request.startAt().minusHours(9),
                            request.endAt().minusHours(9),cursor,BATCH_SIZE);
                    if (candidates.isEmpty()) { break; }
                    appendDeliveryBatch(eventId,request,candidates,workbook);
                    cursor=candidates.getLast();
                }
                try (OutputStream output=Files.newOutputStream(file)) { workbook.writeDeliveryWorkbook(output); }
            }
            // 응답이 시작되기 전 트랜잭션 종료 실패도 임시 파일 누수 없이 처리한다.
            String filename=event.name().replaceAll("[\\r\\n\\\\/:*?\"<>|]","_")+"_배송명단_"
                    +now.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))+".xlsx";
            TemporaryExcelResource resource=new TemporaryExcelResource(file,filename);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    /** 커밋 실패로 응답에 도달하지 못한 파일을 제거한다. */
                    @Override public void afterCompletion(int status) {
                        if (status!=STATUS_COMMITTED) { closeTemporaryFile(resource); }
                    }
                });
            }
            ready=true;
            return resource;
        } catch (IOException exception) {
            throw new CustomException(ErrorCode.REPORT_EXCEL_GENERATION_FAILED,exception);
        } finally {
            if (!ready && file!=null) {
                try { Files.deleteIfExists(file); }
                catch (IOException exception) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("배송 명단 임시 파일 정리 실패"); }
            }
        }
    }

    /** 배치의 현재 자료로 먼저 분류한 뒤 불명확 신청에만 history 조회·해석 비용을 사용한다. */
    private void appendDeliveryBatch(String eventId, RegistrationDeliveryExcelRequest request,List<Candidate> candidates,
            RegistrationDeliveryExcelWriter.WorkbookSession workbook) {
        // 1단계: 금융·현재 예약·선택 기념품을 일괄 조회한다.
        List<String> ids=candidates.stream().map(Candidate::id).toList();
        Map<String,List<PaymentFact>> payments=repository.findPaymentFacts(ids);
        Map<String,List<ReservationFact>> reservations=repository.findReservations(ids,false);
        Set<String> currentCapacityIds=new HashSet<>();
        reservations.values().forEach(values -> values.forEach(r -> r.items().forEach(i -> currentCapacityIds.add(i.capacityId()))));
        Map<String,CapacityInfo> capacities=new HashMap<>(repository.findCapacities(eventId,currentCapacityIds));
        Map<String,List<SouvenirJson>> selections=new HashMap<>();
        Set<String> badJson=new HashSet<>();
        Set<String> souvenirIds=new HashSet<>();
        for (Candidate row:candidates) {
            try {
                List<SouvenirJson> values=resolver.readSouvenirs(row.souvenirsJson());
                selections.put(row.id(),values);
                values.stream().filter(Objects::nonNull).map(SouvenirJson::souvenirId).filter(Objects::nonNull).forEach(souvenirIds::add);
            } catch (JsonProcessingException exception) { badJson.add(row.id()); selections.put(row.id(),List.of()); }
        }
        Map<String,String> names=repository.findSouvenirNames(eventId,souvenirIds);
        List<ExportRow> pending=new ArrayList<>();
        for (Candidate row:candidates) {
            Classification decision=classifier.classifyRegistrationForDelivery(row,payments.getOrDefault(row.id(),List.of()),
                    reservations.getOrDefault(row.id(),List.of()),request.startAt().minusHours(9),request.endAt().minusHours(9));
            if (decision.excluded()) { continue; }
            CurrentSelection current=resolver.resolveCurrentSelection(row,selections.get(row.id()),names,
                    reservations.getOrDefault(row.id(),List.of()),capacities);
            List<String> reasons=new ArrayList<>(decision.reasons()); reasons.addAll(current.reasons());
            if (badJson.contains(row.id())) { reasons.add("기념품 선택 JSON 해석 불가"); }
            Classification finalDecision=new Classification(false,reasons.stream().distinct().toList());
            Selection outputSelection=badJson.contains(row.id()) ? new Selection(row.categoryName(),"판별 불가","판별 불가") : current.selection();
            ExportRow output=new ExportRow(row,outputSelection,finalDecision,null);
            if (finalDecision.unclear()) { pending.add(output); } else { workbook.appendDeliveryRow(output); }
        }

        // 2단계: 불명확 대상의 과거 자원과 개별 환불을 같은 스냅샷에서 조회한다.
        if (pending.isEmpty()) { return; }
        List<String> pendingIds=pending.stream().map(r -> r.candidate().id()).toList();
        Map<String,List<ReservationFact>> histories=repository.findReservations(pendingIds,true);
        Map<String,List<RefundFact>> refunds=repository.findUnclearRefundHistory(pendingIds);
        Map<String,HistoryInput> inputs=new HashMap<>();
        Set<String> historicalIds=new HashSet<>();
        for (ExportRow row:pending) {
            List<ReservationFact> values=histories.getOrDefault(row.candidate().id(),List.of());
            HistoryInput input=values.size()==1 ? resolver.readHistory(values.getFirst().history())
                    : new HistoryInput(List.of(),"예약 누락 또는 중복으로 이력 선택 불가");
            inputs.put(row.candidate().id(),input);
            input.entries().forEach(e -> e.items().forEach(i -> historicalIds.add(i.capacityId())));
        }
        historicalIds.removeAll(capacities.keySet());
        capacities.putAll(repository.findCapacities(eventId,historicalIds));
        for (ExportRow row:pending) {
            // 정보 누락만으로 분리된 참가 확정 건은 현재의 금융·예약 정합성이 확인된 경우에만 최신 구성을 채택한다.
            boolean currentConfirmed="CONFIRMED".equals(row.candidate().status())
                    && row.classification().reasons().stream().allMatch(reason ->
                        reason.equals("신청자·종목·배송지 정보 확인 필요") || reason.equals("최초 승인일 확인 불가·기간 판정 불가"));
            HistoryResult result=resolver.resolveRegistrationHistory(inputs.get(row.candidate().id()),capacities,
                    payments.getOrDefault(row.candidate().id(),List.of()),currentConfirmed);
            // 설명용 금융 이력을 합치되 기존 명단 분류와 확정 정보 판정은 유지한다.
            List<PaymentFact> participantPayments=payments.getOrDefault(row.candidate().id(),List.of());
            List<ReviewEvent> events=resolver.describeUnclearReservationHistory(inputs.get(row.candidate().id()),
                    capacities,participantPayments);
            UnclearReview review=unclearReviewFormatter.formatUnclearRegistrationReview(row.classification(),result,
                    events,participantPayments,refunds.getOrDefault(row.candidate().id(),List.of()));
            workbook.appendDeliveryRow(new ExportRow(row.candidate(),row.current(),row.classification(),result,review));
        }
    }

    /** 시분초 단위 KST 범위를 검증하며 임의 기간 길이 제한은 두지 않는다. */
    private void validateDeliveryPeriod(String eventId,RegistrationDeliveryExcelRequest request) {
        if (eventId==null || eventId.isBlank() || request==null || request.startAt()==null || request.endAt()==null
                || request.startAt().getNano()!=0 || request.endAt().getNano()!=0
                || request.startAt().getYear()<1000 || request.endAt().getYear()>9999
                || !request.startAt().isBefore(request.endAt())) {
            throw new CustomException(ErrorCode.DELIVERY_EXCEL_PERIOD_INVALID);
        }
    }

    /** 후속 정리 실패는 업무 응답을 덮어쓰지 않고 개인정보 없이 기록한다. */
    private void closeTemporaryFile(TemporaryExcelResource resource) {
        try { resource.close(); }
        catch (IOException exception) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("배송 명단 롤백 파일 정리 실패"); }
    }
}
