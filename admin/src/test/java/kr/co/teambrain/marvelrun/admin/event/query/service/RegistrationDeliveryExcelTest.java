package kr.co.teambrain.marvelrun.admin.event.query.service;

import com.fasterxml.jackson.databind.json.JsonMapper;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.common.exception.GlobalExceptionHandler;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.query.controller.RegistrationExcelController;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationDeliveryExcelRequest;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.RegistrationDailyReportExcelWriter;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.RegistrationDeliveryExcelWriter;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.RegistrationDeliveryReportModels.*;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationDeliveryQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationReservationHistoryResolver;
import kr.co.teambrain.marvelrun.admin.event.query.support.TemporaryExcelResource;
import kr.co.teambrain.marvelrun.admin.event.query.util.RegistrationDeliveryClassifier;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** DB 없이 요청·분류·실제 XLSX·파일 수명과 두 단계 연결을 함께 검증한다. */
class RegistrationDeliveryExcelTest {
    private static final LocalDateTime UTC=LocalDateTime.of(2026,10,2,15,0);
    private static final RegistrationDeliveryExcelRequest PERIOD=new RegistrationDeliveryExcelRequest(UTC.plusHours(9),UTC.plusDays(1).plusHours(9));
    @TempDir Path temp;

    /** 제외 상태는 승인일 누락과 예약 부재를 검사하기 전에 제외한다. */
    @ParameterizedTest
    @ValueSource(strings={"PENDING","PAYMENT_PENDING","CANCELLATION_PENDING","CANCELED","EXPIRED"})
    void excludesNonParticipantsBeforeEvidenceChecks(String state) {
        Classification result=new RegistrationDeliveryClassifier().classifyRegistrationForDelivery(candidate(state,false,null),List.of(),List.of(),UTC,UTC.plusDays(1));
        assertThat(result.excluded()).isTrue(); assertThat(result.reasons()).isEmpty();
    }

    /** 정상 외부결제는 PG 상태가 없어도 포함하고 과거 실패 주문은 불명확 사유로 사용하지 않는다. */
    @Test
    void includesOfflineCompletionAndIgnoresOldFailedAttempt() {
        List<PaymentFact> facts=List.of(payment("COMPLETED",BigDecimal.ZERO,false),payment("FAILED",BigDecimal.ZERO,false));
        Classification result=classify(candidate("CONFIRMED",false,UTC),facts);
        assertThat(result.excluded()).isFalse(); assertThat(result.unclear()).isFalse();
        assertThat(classify(candidate("CONFIRMED",true,UTC),facts).excluded()).isTrue();
    }

    /** 미정산·UNKNOWN·승인일 누락과 시작 포함/종료 제외를 판정한다. */
    @Test
    void separatesUnsettledAndUnknownDateAndHonorsPeriodBoundaries() {
        assertThat(classify(candidate("ADDITIONAL_PAYMENT_REQUIRED",false,UTC),List.of(payment("COMPLETED",BigDecimal.ZERO,false))).reasons()).contains("추가결제 필요");
        assertThat(classify(candidate("PARTIAL_REFUND_REQUIRED",false,UTC),List.of(payment("COMPLETED",BigDecimal.ZERO,false))).reasons()).contains("부분환불 필요");
        assertThat(classify(candidate("CONFIRMED",false,null),List.of()).reasons()).contains("최초 승인일 확인 불가·기간 판정 불가");
        assertThat(classify(candidate("CONFIRMED",false,UTC),List.of(payment("UNKNOWN",BigDecimal.ZERO,false))).unclear()).isTrue();
        assertThat(classify(candidate("CONFIRMED",false,UTC.plusDays(1)),List.of(payment("COMPLETED",BigDecimal.ZERO,false))).excluded()).isTrue();
    }

    /** 전액 반환과 처리 중 환불을 구분하고 부분 환불 자체로 신청을 제외하지 않는다. */
    @Test
    void excludesOnlyVerifiedFullyReturnedParticipant() {
        assertThat(classify(candidate("CONFIRMED",false,UTC),List.of(payment("COMPLETED",new BigDecimal("40000"),false))).excluded()).isTrue();
        assertThat(classify(candidate("CONFIRMED",false,UTC),List.of(payment("COMPLETED",new BigDecimal("10000"),false))).excluded()).isFalse();
        assertThat(classify(candidate("CONFIRMED",false,UTC),List.of(payment("COMPLETED",BigDecimal.ZERO,true))).unclear()).isTrue();
    }

    /** 100행 플러시 이후 상단 집계·4개 시트·긴 이력·UUID 미출력·KST를 실제 파일에서 확인한다. */
    @Test
    void workbookKeepsHeadersCountsAndHistoryAfterStreamingFlush() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        RegistrationDeliveryExcelWriter writer=new RegistrationDeliveryExcelWriter();
        try (RegistrationDeliveryExcelWriter.WorkbookSession book=writer.openDeliveryWorkbook(new EventInfo("event","대회",UTC),PERIOD,UTC.plusHours(9))) {
            Candidate normal=candidate("CONFIRMED",false,UTC);
            for (int i=0;i<105;i++) { book.appendDeliveryRow(new ExportRow(normal,new Selection("A","티셔츠","S"),new Classification(false,List.of()),null)); }
            Candidate group=new Candidate("secret-uuid","ADDITIONAL_PAYMENT_REQUIRED",false,"단체원","1990-01-01","01012345678",
                    "org","달리기팀","category","C","[]","단체 주소","단체 상세",UTC.plusHours(9),UTC,new BigDecimal("60000"),new BigDecimal("40000"));
            book.appendDeliveryRow(new ExportRow(group,new Selection("C","티셔츠","L"),new Classification(false,List.of("추가결제 필요")),
                    new HistoryResult(Selection.unknown(),"판별 불가","최근 확정 시점 구분 불가","변경".repeat(18000))));
            book.writeDeliveryWorkbook(bytes);
        }
        try (XSSFWorkbook actual=new XSSFWorkbook(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertThat(actual.getNumberOfSheets()).isEqualTo(4);
            assertThat(actual.getSheetAt(0).getRow(3).getCell(0).getStringCellValue()).contains("시트 인원: 105");
            assertThat(actual.getSheetAt(0).getRow(7).getCell(8).getLocalDateTimeCellValue()).isEqualTo(UTC.plusHours(9));
            assertThat(actual.getSheetAt(0).getRow(7).getCell(9).getLocalDateTimeCellValue()).isEqualTo(UTC.plusHours(9));
            Sheet unclear=actual.getSheet("단체신청불명확명단");
            Row data=unclear.getRow(7);
            assertThat(data.getCell(3).getStringCellValue()).isEqualTo("달리기팀");
            assertThat(data.getCell(7).getStringCellValue()).isEqualTo("단체 주소");
            StringBuilder history=new StringBuilder();
            for (int i=18;i<data.getLastCellNum();i++) { history.append(data.getCell(i).getStringCellValue()); }
            assertThat(history.toString()).isEqualTo("변경".repeat(18000));
            for (Sheet sheet:actual) { for (Row row:sheet) { row.forEach(cell -> assertThat(cell.toString()).doesNotContain("secret-uuid")); } }
        }
    }

    /** 잘못된 입력을 기존 오류 DTO로 반환하고 성공 응답에서 스트림과 임시 파일을 정리한다. */
    @Test
    void mvcBindsSecondsReturnsErrorsAndDownloadsResource() throws Exception {
        RegistrationDeliveryExcelService delivery=mock(RegistrationDeliveryExcelService.class);
        MockMvc mvc=mvc(delivery);
        mvc.perform(get("/v1/admin/registrations/event/delivery-list/excel/download").param("startAt","bad").param("endAt","2026-10-13T00:00:00"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("DELIVERY_EXCEL_PERIOD_INVALID"));
        verifyNoInteractions(delivery);
        Path path=temp.resolve("response.xlsx"); Files.write(path,new byte[]{1,2,3});
        when(delivery.createRegistrationDeliveryExcel(eq("event"),any())).thenReturn(new TemporaryExcelResource(path,"배송.xlsx"));
        mvc.perform(get("/v1/admin/registrations/event/delivery-list/excel/download").param("startAt","2026-10-03T00:00:00").param("endAt","2026-10-04T00:00:00"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(content().bytes(new byte[]{1,2,3}));
        assertThat(path).doesNotExist();
    }

    /** 스트림을 열기 전 응답 실패도 요청 종료 필터가 정리한다. */
    @Test
    void filterCleansFileWhenResponseFailsBeforeOpeningStream() throws Exception {
        Path path=temp.resolve("failed.xlsx"); Files.writeString(path,"fixture");
        MockHttpServletRequest request=new MockHttpServletRequest();
        new TemporaryExcelResource(path,"fixture.xlsx").attachTo(request);
        assertThatThrownBy(() -> new TemporaryExcelResource.CleanupFilter().doFilter(request,new MockHttpServletResponse(),
                (incoming,outgoing) -> { throw new IOException("응답 실패"); })).isInstanceOf(IOException.class);
        assertThat(path).doesNotExist();
    }

    /** 응답 변환기의 존재 확인이나 범위 전송을 위한 재개방이 파일을 먼저 삭제하지 않는다. */
    @Test
    void resourceCanBeReopenedUntilRequestFinishes() throws Exception {
        Path path=temp.resolve("reopen.xlsx"); Files.writeString(path,"fixture");
        try (TemporaryExcelResource resource=new TemporaryExcelResource(path,"fixture.xlsx")) {
            assertThat(resource.exists()).isTrue();
            for (int attempt=0;attempt<2;attempt++) {
                try (InputStream input=resource.getInputStream()) { assertThat(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("fixture"); }
            }
            assertThat(path).exists();
        }
        assertThat(path).doesNotExist();
    }

    /** 정상 신청만 있는 배치는 history 조회를 전혀 요청하지 않는다. */
    @Test
    void normalBatchNeverLoadsHistoryAndValidatesReversedPeriod() throws Exception {
        RegistrationDeliveryQueryRepository repository=mock(RegistrationDeliveryQueryRepository.class);
        Candidate normal=candidate("CONFIRMED",false,UTC);
        when(repository.findDeliveryEvent("event")).thenReturn(Optional.of(new EventInfo("event","대회",UTC)));
        when(repository.findDeliveryCandidates(eq("event"),any(),any(),isNull(),eq(100))).thenReturn(List.of(normal));
        when(repository.findPaymentFacts(anyList())).thenReturn(Map.of("registration",List.of(payment("COMPLETED",BigDecimal.ZERO,false))));
        when(repository.findReservations(anyList(),eq(false))).thenReturn(Map.of("registration",List.of(reservation())));
        when(repository.findCapacities(eq("event"),anyCollection())).thenReturn(Map.of(
                "total",new CapacityInfo("total","EVENT_TOTAL",null,null,"",List.of(),List.of()),
                "category-cap",new CapacityInfo("category-cap","CATEGORY",null,null,"",List.of("category"),List.of("A"))));
        RegistrationDeliveryExcelService service=new RegistrationDeliveryExcelService(repository,new RegistrationDeliveryClassifier(),
                new RegistrationReservationHistoryResolver(JsonMapper.builder().findAndAddModules().build()),new RegistrationDeliveryExcelWriter(),
                new ServerTimeProvider(Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"),ZoneId.of("Asia/Seoul"))));
        try (TemporaryExcelResource resource=service.createRegistrationDeliveryExcel("event",PERIOD);
             InputStream input=resource.getInputStream(); XSSFWorkbook book=new XSSFWorkbook(input)) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(7);
            assertThat(book.getSheetAt(2).getLastRowNum()).isEqualTo(6);
        }
        verify(repository,never()).findReservations(anyList(),eq(true));
        assertThatThrownBy(() -> service.createRegistrationDeliveryExcel("event",new RegistrationDeliveryExcelRequest(PERIOD.endAt(),PERIOD.startAt())))
                .isInstanceOfSatisfying(CustomException.class,e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DELIVERY_EXCEL_PERIOD_INVALID));
    }

    /** 불명확 대상의 손상된 이력은 행 누락이나 전체 파일 실패로 숨기지 않는다. */
    @Test
    void unclearBatchKeepsParticipantAndExplainsMalformedHistory() throws Exception {
        RegistrationDeliveryQueryRepository repository=mock(RegistrationDeliveryQueryRepository.class);
        Candidate pending=candidate("ADDITIONAL_PAYMENT_REQUIRED",false,UTC);
        when(repository.findDeliveryEvent("event")).thenReturn(Optional.of(new EventInfo("event","대회",UTC)));
        when(repository.findDeliveryCandidates(eq("event"),any(),any(),isNull(),eq(100))).thenReturn(List.of(pending));
        when(repository.findPaymentFacts(anyList())).thenReturn(Map.of("registration",List.of(payment("COMPLETED",BigDecimal.ZERO,false))));
        when(repository.findReservations(anyList(),eq(false))).thenReturn(Map.of("registration",List.of(reservation())));
        when(repository.findReservations(anyList(),eq(true))).thenReturn(Map.of("registration",List.of(
                new ReservationFact("registration","reservation","CONSUMED","not-json",reservation().items()))));
        RegistrationDeliveryExcelService service=new RegistrationDeliveryExcelService(repository,new RegistrationDeliveryClassifier(),
                new RegistrationReservationHistoryResolver(JsonMapper.builder().findAndAddModules().build()),new RegistrationDeliveryExcelWriter(),
                new ServerTimeProvider(Clock.system(ZoneId.of("Asia/Seoul"))));

        // 최초 분류의 불명확 사유와 이력 해석 실패 사유를 서로 다른 열에 남긴다.
        try (TemporaryExcelResource resource=service.createRegistrationDeliveryExcel("event",PERIOD);
             InputStream input=resource.getInputStream(); XSSFWorkbook book=new XSSFWorkbook(input)) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(6);
            Row row=book.getSheetAt(2).getRow(7);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("테스트");
            assertThat(row.getCell(14).getStringCellValue()).contains("추가결제 필요");
            assertThat(row.getCell(16).getStringCellValue()).contains("JSON 해석 불가");
        }
        verify(repository).findReservations(List.of("registration"),true);
    }

    /** 기존 컨트롤러의 다른 의존성은 실행하지 않는 MVC 검증 준비다. */
    private MockMvc mvc(RegistrationDeliveryExcelService delivery) {
        RegistrationExcelController controller=new RegistrationExcelController(mock(ServerTimeProvider.class),mock(RegistrationExcelService.class),
                delivery,mock(RegistrationDailyReportService.class),mock(RegistrationDailyReportExcelWriter.class));
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TemporaryExcelResource.CleanupFilter()).build();
    }

    /** 판정 테스트에 필요한 정상 현재 예약을 공유한다. */
    private ReservationFact reservation() {
        return new ReservationFact("registration","reservation","CONSUMED",null,List.of(new CapacityItem("total",1),new CapacityItem("category-cap",1)));
    }

    /** 동일한 판정 범위로 상태별 차이를 비교한다. */
    private Classification classify(Candidate candidate,List<PaymentFact> facts) {
        return new RegistrationDeliveryClassifier().classifyRegistrationForDelivery(candidate,facts,List.of(reservation()),UTC,UTC.plusDays(1));
    }

    /** 운영 개인정보를 사용하지 않는 신청 자료다. */
    private Candidate candidate(String state,boolean deleted,LocalDateTime approved) {
        return new Candidate("registration",state,deleted,"테스트","1990-01-01","01012345678",null,null,"category","A","[]",
                "주소","상세",UTC.plusHours(9),approved,new BigDecimal("40000"),new BigDecimal("40000"));
    }

    /** PG 전용 상태가 없는 완료 결제도 검증하는 금융 자료다. */
    private PaymentFact payment(String state,BigDecimal returned,boolean unsettled) {
        return new PaymentFact("registration","payment","REGISTRATION_TRY",state,UTC,new BigDecimal("40000"),returned,unsettled,false,null);
    }
}
