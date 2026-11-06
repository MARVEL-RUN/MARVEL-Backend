package kr.co.teambrain.marvelrun.admin.event.query.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.policy.VirtualRounds;
import kr.co.teambrain.marvelrun.admin.event.policy.VirtualRounds.Round;
import kr.co.teambrain.marvelrun.admin.event.policy.VirtualRounds.RosterRange;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualPackingListExcelWriter;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualPrintListExcelWriter;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualRosterModels;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualRosterModels.AddressParts;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualRosterModels.PackingRow;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualRosterModels.PrintRow;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationDeliveryQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.VirtualRosterQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.VirtualRosterQueryRepository.EventInfo;
import kr.co.teambrain.marvelrun.admin.event.query.repository.VirtualRosterQueryRepository.PackingLine;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationActionPolicyReader;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationReservationHistoryResolver;
import kr.co.teambrain.marvelrun.admin.event.query.support.TemporaryExcelResource;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 버추얼런 한 차수의 인쇄 업체용·패킹 업체용 명단 엑셀을 만든다.
 * 차수는 마감 시각으로 찾는다. 대상은 앞 차수 끝부터 이 차수 끝까지(1차는 처음부터, 마지막 차수는 끝까지) 신청한
 * 미삭제 확정 신청(외부 결제 포함)이다. 그래서 차수 사이 빈 기간이나 마지막 마감 뒤에 찍힌 신청도 한 차수 명단에 들어간다.
 */
@Service
@RequiredArgsConstructor
public class VirtualRosterExcelService {
    private static final String FREE_SIZE = "FREE";

    private final VirtualRosterQueryRepository repository;
    private final RegistrationDeliveryQueryRepository deliveryRepository;
    private final RegistrationActionPolicyReader policyReader;
    private final RegistrationReservationHistoryResolver resolver;
    private final VirtualPrintListExcelWriter printWriter;
    private final VirtualPackingListExcelWriter packingWriter;
    private final ServerTimeProvider timeProvider;

    /** 인쇄 업체용 명단이다. 참가 항목 하나가 한 행이다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public TemporaryExcelResource createPrintList(String eventId, LocalDateTime deadline) {
        LocalDateTime now = timeProvider.currentDateTime();
        EventInfo event = loadEvent(eventId);
        List<Round> rounds = loadRounds(eventId);
        Round round = VirtualRounds.requireByDeadline(rounds, deadline);
        RosterRange range = VirtualRounds.rosterRange(rounds, round);
        String roundLabel = round.number() + "차";
        List<PrintRow> rows = repository.findPrintEntries(eventId, range.from(), range.to()).stream()
                .map(entry -> new PrintRow(entry.bib() == null ? "" : entry.bib(), entry.name(),
                        VirtualRosterModels.stripDistance(entry.categoryName()), roundLabel))
                .toList();
        return createFile("marvelrun-print-", filename(event, "인쇄명단", round, now), output -> printWriter.write(output, rows));
    }

    /** 패킹 업체용 명단이다. 참가 항목 하나가 한 행이며 같은 신청 번호의 행이 한 상자다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public TemporaryExcelResource createPackingList(String eventId, LocalDateTime deadline) {
        LocalDateTime now = timeProvider.currentDateTime();
        EventInfo event = loadEvent(eventId);
        List<Round> rounds = loadRounds(eventId);
        Round round = VirtualRounds.requireByDeadline(rounds, deadline);
        RosterRange range = VirtualRounds.rosterRange(rounds, round);
        List<PackingLine> lines = repository.findPackingLines(eventId, range.from(), range.to());
        List<PackingRow> rows = buildPackingRows(eventId, round.number() + "차", lines);
        return createFile("marvelrun-packing-", filename(event, "패킹명단", round, now),
                output -> packingWriter.write(output, rows));
    }

    /**
     * 참가 항목 행을 엑셀 행으로 바꾼다. 기념품은 구매 줄(구매 줄이 없으면 신청)의 첫 행에만 적어
     * 패키지 하나의 키트가 여러 개로 읽히지 않게 한다. 기념품 이름은 한 번에 읽는다.
     */
    private List<PackingRow> buildPackingRows(String eventId, String roundLabel, List<PackingLine> lines) {
        Map<String, List<SouvenirJson>> souvenirs = new HashMap<>();
        Set<String> souvenirIds = new HashSet<>();
        for (PackingLine line : lines) {
            String key = souvenirKey(line);
            if (souvenirs.containsKey(key)) { continue; }
            List<SouvenirJson> values;
            try {
                values = resolver.readSouvenirs(line.souvenirJson());
            } catch (JsonProcessingException exception) {
                values = null;
            }
            souvenirs.put(key, values);
            if (values != null) {
                values.stream().filter(Objects::nonNull).map(SouvenirJson::souvenirId).filter(Objects::nonNull).forEach(souvenirIds::add);
            }
        }
        Map<String, String> names = deliveryRepository.findSouvenirNames(eventId, souvenirIds);
        List<PackingRow> rows = new ArrayList<>(lines.size());
        Set<String> written = new HashSet<>();
        for (PackingLine line : lines) {
            String key = souvenirKey(line);
            String souvenir = written.add(key) ? souvenirText(souvenirs.get(key), names) : "";
            AddressParts address = VirtualRosterModels.splitAddress(line.address());
            rows.add(new PackingRow(line.registrationId(), roundLabel, productLabel(line.productType()), line.productName(),
                    line.entryCategoryName() == null ? "" : VirtualRosterModels.stripDistance(line.entryCategoryName()),
                    line.bib() == null ? "" : line.bib(), line.entryName() == null ? "" : line.entryName(),
                    souvenir, line.receiver(), line.phone(), address.zipcode(), address.address(),
                    line.addressDetail() == null ? "" : line.addressDetail()));
        }
        return rows;
    }

    /** 기념품을 묶는 단위다. 구매 줄이 있으면 줄, 없으면 신청이다. */
    private String souvenirKey(PackingLine line) {
        return line.itemId() == null ? "registration:" + line.registrationId() : "item:" + line.itemId();
    }

    /** "이름" 또는 "이름(사이즈)"를 ", "로 잇는다. FREE 사이즈는 쓰지 않고, JSON을 못 읽으면 확인 문구를 쓴다. */
    private String souvenirText(List<SouvenirJson> souvenirs, Map<String, String> names) {
        if (souvenirs == null) { return "기념품 확인 필요"; }
        return souvenirs.stream().filter(Objects::nonNull).map(souvenir -> {
            String name = names.getOrDefault(souvenir.souvenirId(), souvenir.souvenirId());
            String size = souvenir.selectedSize();
            boolean showSize = size != null && !size.isBlank() && !FREE_SIZE.equalsIgnoreCase(size.trim());
            return name + (showSize ? "(" + size.trim() + ")" : "");
        }).collect(Collectors.joining(", "));
    }

    private String productLabel(String productType) {
        return "SINGLE".equals(productType) ? "단품" : "PACKAGE".equals(productType) ? "패키지" : "";
    }

    private EventInfo loadEvent(String eventId) {
        EventInfo event = repository.findEvent(eventId).orElseThrow(() -> new CustomException(ErrorCode.EVENT_NOT_FOUND));
        if (!"VIRTUAL".equals(event.format())) {
            throw new CustomException(ErrorCode.VIRTUAL_EVENT_REQUIRED);
        }
        return event;
    }

    private List<Round> loadRounds(String eventId) {
        return VirtualRounds.fromPolicies(policyReader.loadEnabledRegistrationActionPolicies(eventId, VirtualRounds.ROUND_ACTION_TYPES));
    }

    private String filename(EventInfo event, String kind, Round round, LocalDateTime now) {
        return event.name().replaceAll("[\\r\\n\\\\/:*?\"<>|]", "_") + "_" + kind + "_" + round.number() + "차_"
                + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmm")) + ".xlsx";
    }

    /** 파일에 쓰는 동작이다. */
    private interface FileWriter {
        void write(OutputStream output) throws IOException;
    }

    /** 완성된 임시 파일만 반환하고, 만들다 실패하거나 커밋에 실패하면 파일을 지운다. */
    private TemporaryExcelResource createFile(String prefix, String filename, FileWriter writer) {
        Path file = null;
        boolean ready = false;
        try {
            file = Files.createTempFile(prefix, ".xlsx");
            try (OutputStream output = Files.newOutputStream(file)) { writer.write(output); }
            TemporaryExcelResource resource = new TemporaryExcelResource(file, filename);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    /** 커밋 실패로 응답에 도달하지 못한 파일을 제거한다. */
                    @Override public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) { closeTemporaryFile(resource); }
                    }
                });
            }
            ready = true;
            return resource;
        } catch (IOException exception) {
            throw new CustomException(ErrorCode.REPORT_EXCEL_GENERATION_FAILED, exception);
        } finally {
            if (!ready && file != null) {
                try { Files.deleteIfExists(file); }
                catch (IOException exception) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("버추얼런 명단 임시 파일 정리 실패"); }
            }
        }
    }

    /** 후속 정리 실패는 업무 응답을 덮어쓰지 않고 개인정보 없이 기록한다. */
    private void closeTemporaryFile(TemporaryExcelResource resource) {
        try { resource.close(); }
        catch (IOException exception) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("버추얼런 명단 롤백 파일 정리 실패"); }
    }
}
