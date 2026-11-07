package kr.co.teambrain.marvelrun.admin.event.query.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyModels.Action;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyModels.RegistrationActionPolicyInput;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualPackingListExcelWriter;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualPrintListExcelWriter;
import kr.co.teambrain.marvelrun.admin.event.query.repository.EventCategorySouvenirQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.SouvenirQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.VirtualRosterQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.VirtualRosterQueryRepository.EventInfo;
import kr.co.teambrain.marvelrun.admin.event.query.repository.VirtualRosterQueryRepository.PackingLine;
import kr.co.teambrain.marvelrun.admin.event.query.repository.VirtualRosterQueryRepository.PrintEntry;
import kr.co.teambrain.marvelrun.admin.event.query.repository.projection.RegistrationOptionProjection;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationActionPolicyReader;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationReservationHistoryResolver;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationRowSouvenirs;
import kr.co.teambrain.marvelrun.admin.event.query.support.TemporaryExcelResource;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationActionType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VirtualRosterExcelServiceTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 1, 0, 0);
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 11, 20, 17, 0);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 21, 9, 5, 30);

    @Mock VirtualRosterQueryRepository repository;
    @Mock EventCategorySouvenirQueryRepository souvenirMappingRepository;
    @Mock SouvenirQueryRepository souvenirRepository;
    @Mock RegistrationActionPolicyReader policyReader;
    @Mock ServerTimeProvider timeProvider;
    VirtualRosterExcelService service;

    @BeforeEach
    void setUp() {
        when(timeProvider.currentDateTime()).thenReturn(NOW);
        RegistrationRowSouvenirs rowSouvenirs = new RegistrationRowSouvenirs(
                new RegistrationReservationHistoryResolver(new ObjectMapper()), souvenirMappingRepository, souvenirRepository);
        service = new VirtualRosterExcelService(repository, policyReader, rowSouvenirs,
                new VirtualPrintListExcelWriter(), new VirtualPackingListExcelWriter(), timeProvider);
    }

    /** 버추얼이 아닌 대회는 거절한다. */
    @Test
    void rejectsNonVirtualEvent() {
        when(repository.findEvent("e")).thenReturn(Optional.of(new EventInfo("대회", "OFFLINE")));

        assertError(() -> service.createPrintList("e", DEADLINE), ErrorCode.VIRTUAL_EVENT_REQUIRED);
        assertError(() -> service.createPackingList("e", DEADLINE), ErrorCode.VIRTUAL_EVENT_REQUIRED);
    }

    /** 마감 시각이 어느 차수와도 맞지 않으면 거절한다. */
    @Test
    void rejectsUnknownDeadline() {
        when(repository.findEvent("e")).thenReturn(Optional.of(new EventInfo("대회", "VIRTUAL")));
        when(policyReader.loadEnabledRegistrationActionPolicies(any(String.class), anyCollection()))
                .thenReturn(round());

        assertError(() -> service.createPrintList("e", DEADLINE.minusDays(1)), ErrorCode.VIRTUAL_ROUND_NOT_FOUND);
        assertError(() -> service.createPackingList("e", DEADLINE.minusDays(1)), ErrorCode.VIRTUAL_ROUND_NOT_FOUND);
    }

    /** 인쇄 명단 파일명과 내용이 차수·시각 규칙을 따른다. */
    @Test
    void printListFileNameAndRows() throws Exception {
        when(repository.findEvent("e")).thenReturn(Optional.of(new EventInfo("마블/런", "VIRTUAL")));
        when(policyReader.loadEnabledRegistrationActionPolicies(any(String.class), anyCollection())).thenReturn(round());
        when(repository.findPrintEntries("e", null, null)).thenReturn(List.of(
                new PrintEntry("10001", "가", "5km | 어벤저스"), new PrintEntry(null, "나", "엑스맨")));

        try (TemporaryExcelResource file = service.createPrintList("e", DEADLINE)) {
            assertThat(file.getFilename()).isEqualTo("마블_런_인쇄명단_1차_202611210905.xlsx");
            Sheet sheet = open(file);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("10001");
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("어벤저스");
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("1차");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEmpty();
        }
    }

    /**
     * 상자에 넣을 물건 수가 늘거나 빠지지 않게, 구매 줄의 기념품 하나는 한 행에만 적는다.
     * 여러 종목에 연결된 기념품은 연결된 첫 행에, 패키지에만 연결돼 어느 행 종목에도 없는 기념품은 줄의 첫 행에 적는다.
     */
    @Test
    void packingListWritesEachLineSouvenirOnce() throws Exception {
        givenRound();
        String souvenirs = "[{\"souvenirId\":\"kit-x\",\"selectedSize\":\"FREE\"},{\"souvenirId\":\"medal\",\"selectedSize\":\"FREE\"},"
                + "{\"souvenirId\":\"bag\",\"selectedSize\":\"FREE\"}]";
        when(repository.findPackingLines("e", null, null)).thenReturn(List.of(
                line("r1", "i1", "3종 패키지", "PACKAGE", souvenirs, "a", "어벤저스", null, "가"),
                line("r1", "i1", "3종 패키지", "PACKAGE", souvenirs, "x", "엑스맨", null, "나"),
                line("r1", "i1", "3종 패키지", "PACKAGE", souvenirs, "f", "판타스틱4", null, "다")));
        givenLinks(link("x", "kit-x"), link("x", "medal"), link("f", "medal"), link("pkg", "bag"));
        givenNames(Map.of("kit-x", "엑스맨 키트", "medal", "메달", "bag", "가방"));

        try (TemporaryExcelResource file = service.createPackingList("e", DEADLINE)) {
            Sheet sheet = open(file);
            assertThat(row(sheet, 1).get(7)).isEqualTo("가방");
            assertThat(row(sheet, 2).get(7)).isEqualTo("엑스맨 키트, 메달");
            assertThat(row(sheet, 3).get(7)).isEmpty();
        }
    }

    /** 패킹 명단은 기념품을 "이름(사이즈)"로 잇고 FREE는 뺀다. 주소는 우편번호를 떼고 부문은 거리 접두어를 뗀다. */
    @Test
    void packingListSouvenirsAndAddress() throws Exception {
        givenRound();
        String souvenirs = "[{\"souvenirId\":\"s1\",\"selectedSize\":\"L\"},{\"souvenirId\":\"s2\",\"selectedSize\":\"FREE\"}]";
        when(repository.findPackingLines("e", null, null)).thenReturn(List.of(
                line("r1", "i1", "패키지", "PACKAGE", souvenirs, "a", "5km | 어벤저스", "00001", "가"),
                line("r1", "i1", "패키지", "PACKAGE", souvenirs, "x", "엑스맨", null, "나")));
        givenLinks(link("a", "s1"), link("a", "s2"), link("x", "s2"));
        givenNames(Map.of("s1", "티셔츠", "s2", "메달"));

        try (TemporaryExcelResource file = service.createPackingList("e", DEADLINE)) {
            assertThat(file.getFilename()).startsWith("대회_패킹명단_1차_");
            Sheet sheet = open(file);
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(row(sheet, 1)).containsExactly("r1", "1차", "패키지", "패키지", "어벤저스", "00001", "가",
                    "티셔츠(L), 메달", "신청자", "010-0000-0000", "01234", "서울시", "101호");
            assertThat(row(sheet, 2)).containsExactly("r1", "1차", "패키지", "패키지", "엑스맨", "", "나",
                    "", "신청자", "010-0000-0000", "01234", "서울시", "101호");
        }
    }

    /**
     * 같은 단품 두 개와 패키지를 산 주문은 참가 항목마다 한 행이라 같은 종목 두 사람이 모두 나온다.
     * 기념품은 행마다 그 행 종목에 연결된 것이라 패키지 세 행에는 부문 키트가 하나씩 적힌다.
     * 참가 항목이 없는 구매 줄과 구매 줄이 없는 신청도 한 행이며 그 줄(신청)의 기념품 전부를 적는다.
     */
    @Test
    void packingListWritesDivisionKitOnEveryRow() throws Exception {
        givenRound();
        String avengersKit = "[{\"souvenirId\":\"kit-a\",\"selectedSize\":\"FREE\"}]";
        String xmenKit = "[{\"souvenirId\":\"kit-x\",\"selectedSize\":\"FREE\"}]";
        String packageKits = "[{\"souvenirId\":\"kit-a\",\"selectedSize\":\"FREE\"},"
                + "{\"souvenirId\":\"kit-x\",\"selectedSize\":\"FREE\"},{\"souvenirId\":\"kit-f\",\"selectedSize\":\"FREE\"}]";
        when(repository.findPackingLines("e", null, null)).thenReturn(List.of(
                line("r1", "i1", "어벤저스", "SINGLE", avengersKit, "a", "어벤저스", "10001", "가"),
                line("r1", "i2", "어벤저스", "SINGLE", avengersKit, "a", "어벤저스", "10002", "나"),
                line("r1", "i3", "3종 패키지", "PACKAGE", packageKits, "a", "어벤저스", "10003", "다"),
                line("r1", "i3", "3종 패키지", "PACKAGE", packageKits, "x", "엑스맨", "20001", "라"),
                line("r1", "i3", "3종 패키지", "PACKAGE", packageKits, "f", "판타스틱4", "30001", "마"),
                line("r1", "i4", "엑스맨", "SINGLE", xmenKit, null, null, null, null),
                line("r2", null, "3종 패키지", "PACKAGE", packageKits, null, null, null, null)));
        givenLinks(link("a", "kit-a"), link("x", "kit-x"), link("f", "kit-f"),
                link("pkg", "kit-a"), link("pkg", "kit-x"), link("pkg", "kit-f"));
        givenNames(Map.of("kit-a", "어벤저스 키트", "kit-x", "엑스맨 키트", "kit-f", "판타스틱4 키트"));

        try (TemporaryExcelResource file = service.createPackingList("e", DEADLINE)) {
            Sheet sheet = open(file);
            assertThat(sheet.getLastRowNum()).isEqualTo(7);
            List<List<String>> rows = new java.util.ArrayList<>();
            for (int i = 1; i <= 7; i++) {
                List<String> cells = row(sheet, i);
                rows.add(List.of(cells.get(0), cells.get(2), cells.get(3), cells.get(4), cells.get(5), cells.get(6), cells.get(7)));
            }
            assertThat(rows).containsExactly(
                    List.of("r1", "단품", "어벤저스", "어벤저스", "10001", "가", "어벤저스 키트"),
                    List.of("r1", "단품", "어벤저스", "어벤저스", "10002", "나", "어벤저스 키트"),
                    List.of("r1", "패키지", "3종 패키지", "어벤저스", "10003", "다", "어벤저스 키트"),
                    List.of("r1", "패키지", "3종 패키지", "엑스맨", "20001", "라", "엑스맨 키트"),
                    List.of("r1", "패키지", "3종 패키지", "판타스틱4", "30001", "마", "판타스틱4 키트"),
                    List.of("r1", "단품", "엑스맨", "", "", "", "엑스맨 키트"),
                    List.of("r2", "패키지", "3종 패키지", "", "", "", "어벤저스 키트, 엑스맨 키트, 판타스틱4 키트"));
        }
    }

    /** 기념품 JSON을 못 읽으면 그 구매 줄의 모든 행에 확인 문구를 쓴다. */
    @Test
    void packingListMarksUnreadableSouvenirs() throws Exception {
        givenRound();
        when(repository.findPackingLines("e", null, null)).thenReturn(List.of(
                line("r1", "i1", "패키지", "PACKAGE", "not-json", "a", "어벤저스", null, "가"),
                line("r1", "i1", "패키지", "PACKAGE", "not-json", "x", "엑스맨", null, "나")));

        try (TemporaryExcelResource file = service.createPackingList("e", DEADLINE)) {
            Sheet sheet = open(file);
            assertThat(row(sheet, 1).get(7)).isEqualTo("기념품 확인 필요");
            assertThat(row(sheet, 2).get(7)).isEqualTo("기념품 확인 필요");
        }
    }

    /** 차수가 여럿이면 앞 차수 끝부터 이 차수 끝까지를 넘기고, 1차는 처음부터, 마지막 차수는 끝까지 넘긴다. */
    @Test
    void passesChainedRangePerRound() throws Exception {
        LocalDateTime start2 = DEADLINE.plusDays(3);
        LocalDateTime deadline2 = DEADLINE.plusDays(7);
        LocalDateTime start3 = DEADLINE.plusDays(10);
        LocalDateTime deadline3 = DEADLINE.plusDays(14);
        when(repository.findEvent("e")).thenReturn(Optional.of(new EventInfo("대회", "VIRTUAL")));
        List<RegistrationActionPolicyInput> rows = new java.util.ArrayList<>(round());
        rows.addAll(round(start2, deadline2));
        rows.addAll(round(start3, deadline3));
        when(policyReader.loadEnabledRegistrationActionPolicies(any(String.class), anyCollection())).thenReturn(rows);

        try (TemporaryExcelResource first = service.createPrintList("e", DEADLINE);
             TemporaryExcelResource middle = service.createPrintList("e", deadline2);
             TemporaryExcelResource last = service.createPackingList("e", deadline3)) {
            verify(repository).findPrintEntries("e", null, DEADLINE);
            verify(repository).findPrintEntries("e", DEADLINE, deadline2);
            verify(repository).findPackingLines("e", deadline2, null);
            assertThat(last.getFilename()).startsWith("대회_패킹명단_3차_");
        }
    }

    private void givenRound() {
        when(repository.findEvent("e")).thenReturn(Optional.of(new EventInfo("대회", "VIRTUAL")));
        when(policyReader.loadEnabledRegistrationActionPolicies(any(String.class), anyCollection())).thenReturn(round());
    }

    private PackingLine line(String registrationId, String itemId, String productName, String productType, String souvenirJson,
            String entryCategoryId, String entryCategoryName, String bib, String entryName) {
        return new PackingLine(registrationId, itemId, productName, productType, souvenirJson, entryCategoryId,
                entryCategoryName, bib, entryName, "신청자", "010-0000-0000", "서울시_01234", "101호");
    }

    /** 대회의 종목·기념품 연결이다. */
    private void givenLinks(RegistrationOptionProjection... links) {
        when(souvenirMappingRepository.findRegistrationOptionsProjectionByEventId("e")).thenReturn(List.of(links));
    }

    private static RegistrationOptionProjection link(String categoryId, String souvenirId) {
        RegistrationOptionProjection link = mock(RegistrationOptionProjection.class);
        when(link.getEventCategoryId()).thenReturn(categoryId);
        when(link.getSouvenirId()).thenReturn(souvenirId);
        return link;
    }

    /** 기념품 이름이다. 이름 조회는 구매 줄에서 읽은 기념품 ID로 한 번 부른다. */
    private void givenNames(Map<String, String> names) {
        List<SouvenirQueryRepository.SouvenirNameProjection> projections = names.entrySet().stream().map(entry -> {
            SouvenirQueryRepository.SouvenirNameProjection projection = mock(SouvenirQueryRepository.SouvenirNameProjection.class);
            when(projection.getId()).thenReturn(entry.getKey());
            when(projection.getName()).thenReturn(entry.getValue());
            return projection;
        }).toList();
        when(souvenirRepository.findNamesByIds(any())).thenReturn(projections);
    }

    private List<String> row(Sheet sheet, int index) {
        List<String> cells = new java.util.ArrayList<>();
        sheet.getRow(index).forEach(cell -> cells.add(cell.getStringCellValue()));
        return cells;
    }

    private Sheet open(TemporaryExcelResource file) throws Exception {
        try (InputStream in = file.getInputStream(); XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            return workbook.getSheetAt(0);
        }
    }

    private List<RegistrationActionPolicyInput> round() {
        return round(START, DEADLINE);
    }

    private List<RegistrationActionPolicyInput> round(LocalDateTime start, LocalDateTime deadline) {
        return List.of(Action.MODIFY, Action.PAYMENT, Action.REFUND).stream()
                .map(action -> new RegistrationActionPolicyInput(action.name(), "e", action, start, deadline, deadline, true))
                .toList();
    }

    private void assertError(Runnable run, ErrorCode code) {
        assertThatThrownBy(run::run).isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
