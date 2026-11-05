package kr.co.teambrain.marvelrun.admin.event.query.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyModels.Action;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyModels.RegistrationActionPolicyInput;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualPackingListExcelWriter;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualPrintListExcelWriter;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualRosterModels.Course;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationDeliveryQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.VirtualRosterQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.VirtualRosterQueryRepository.EventInfo;
import kr.co.teambrain.marvelrun.admin.event.query.repository.VirtualRosterQueryRepository.PackingEntry;
import kr.co.teambrain.marvelrun.admin.event.query.repository.VirtualRosterQueryRepository.PackingRegistration;
import kr.co.teambrain.marvelrun.admin.event.query.repository.VirtualRosterQueryRepository.PrintEntry;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationActionPolicyReader;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationReservationHistoryResolver;
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
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VirtualRosterExcelServiceTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 1, 0, 0);
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 11, 20, 17, 0);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 21, 9, 5, 30);

    @Mock VirtualRosterQueryRepository repository;
    @Mock RegistrationDeliveryQueryRepository deliveryRepository;
    @Mock RegistrationActionPolicyReader policyReader;
    @Mock ServerTimeProvider timeProvider;
    VirtualRosterExcelService service;

    @BeforeEach
    void setUp() {
        when(timeProvider.currentDateTime()).thenReturn(NOW);
        service = new VirtualRosterExcelService(repository, deliveryRepository, policyReader,
                new RegistrationReservationHistoryResolver(new ObjectMapper()),
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

    /** 패킹 명단은 기념품을 "이름(사이즈)"로 잇고 FREE는 뺀다. 주소는 우편번호를 뗀다. */
    @Test
    void packingListSouvenirsAndAddress() throws Exception {
        when(repository.findEvent("e")).thenReturn(Optional.of(new EventInfo("대회", "VIRTUAL")));
        when(policyReader.loadEnabledRegistrationActionPolicies(any(String.class), anyCollection())).thenReturn(round());
        when(repository.findChildCourses("e")).thenReturn(List.of(new Course("a", "5km | 어벤저스"), new Course("b", "엑스맨")));
        when(repository.findPackingRegistrations("e", null, null)).thenReturn(List.of(new PackingRegistration("r1", "패키지",
                "PACKAGE", "신청자", "010-0000-0000", "서울시_01234", "101호",
                "[{\"souvenirId\":\"s1\",\"selectedSize\":\"L\"},{\"souvenirId\":\"s2\",\"selectedSize\":\"FREE\"}]")));
        when(repository.findPackingEntries(anyCollection())).thenReturn(List.of(
                new PackingEntry("r1", "a", "00001", "가"), new PackingEntry("r1", "b", null, "나")));
        when(deliveryRepository.findSouvenirNames("e", Set.of("s1", "s2"))).thenReturn(java.util.Map.of("s1", "티셔츠", "s2", "메달"));

        try (TemporaryExcelResource file = service.createPackingList("e", DEADLINE)) {
            assertThat(file.getFilename()).startsWith("대회_패킹명단_1차_");
            Sheet sheet = open(file);
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
            List<String> row = new java.util.ArrayList<>();
            sheet.getRow(1).forEach(cell -> row.add(cell.getStringCellValue()));
            assertThat(row).containsExactly("r1", "1차", "패키지", "패키지", "00001", "가", "", "나",
                    "티셔츠(L), 메달", "신청자", "010-0000-0000", "01234", "서울시", "101호");
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
            verify(repository).findPackingRegistrations("e", deadline2, null);
            assertThat(last.getFilename()).startsWith("대회_패킹명단_3차_");
        }
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
