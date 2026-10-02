package kr.co.teambrain.marvelrun.admin.event.command.application.service;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.dto.CapacityShortage;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.service.*;
import kr.co.teambrain.marvelrun.admin.event.command.application.context.OfflineRegistrationContext;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.*;
import kr.co.teambrain.marvelrun.admin.event.command.application.excel.OfflineRegistrationExcelReader;
import kr.co.teambrain.marvelrun.admin.event.command.application.exception.OfflineRegistrationImportException;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.*;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.admin.event.command.repository.*;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.*;
import kr.co.teambrain.marvelrun.admin.common.exception.GlobalExceptionHandler;
import kr.co.teambrain.marvelrun.admin.event.command.application.controller.RegistrationCommandController;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 업로드 파일 해석부터 오류 응답·적재 입력까지 기능별 메서드로 검증한다. */
class OfflineRegistrationImportTest {
    private final OfflineRegistrationExcelReader reader = new OfflineRegistrationExcelReader();
    private final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private final EventCommandRepository events = mock(EventCommandRepository.class);
    private final RegistrationCommandRepository registrations = mock(RegistrationCommandRepository.class);
    private final EventCategoryCommandRepository categories = mock(EventCategoryCommandRepository.class);
    private final EventCategorySouvenirCommandRepository mappings = mock(EventCategorySouvenirCommandRepository.class);
    private final RegistrationPolicyLoader loader = mock(RegistrationPolicyLoader.class);
    private final OfflineRegistrationImportValidator validator = new OfflineRegistrationImportValidator(events,
            categories, mappings, registrations, loader, mock(RegistrationPolicyValidator.class), factory.getValidator());

    /** Bean Validation의 리소스를 테스트마다 정리한다. */
    @AfterEach
    void closeValidationFactory() { factory.close(); }

    /** 실제 배포 V3의 수식 캐시가 비어 있어도 매핑 가격은 정상 해석한다. */
    @Test
    void readsDistributedV3MappingWithoutFormulaCalculation() throws Exception {
        Path template = Path.of("../docs/reports/엑셀개인신청양식/v3/마블런2026_현장개인신청양식_V3.xlsx");
        List<OfflineRegistrationExcelRow> mapping = new ArrayList<>();
        List<Integer> batchSizes = new ArrayList<>();
        reader.readOfflineRegistrationWorkbook(new MockMultipartFile("file", Files.readAllBytes(template)),
                mapping::addAll, batch -> batchSizes.add(batch.size()));
        OfflineRegistrationImportResult result = new OfflineRegistrationImportResult();
        OfflineRegistrationExcelMapping parsed = validator.parseOfflineMapping("marvelrun2026", mapping, result);

        assertThat(result.errors()).isEmpty();
        assertThat(parsed.prices().get("5km").adultAmount()).isEqualByComparingTo("35000");
        assertThat(parsed.prices().get("10km").childAmount()).isNull();
        assertThat(batchSizes).allMatch(size -> size <= 100);
        assertThat(batchSizes.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1006);
    }

    /** 결제 CHECK와 decimal(12,2) 범위 안의 양의 원화 정수만 허용한다. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,false", "-1,false", "10000000000,false", "1.5,false", "9999999999,true"})
    void validatesMappingAmountAgainstPaymentConstraints(String amount, boolean allowed) throws Exception {
        // 기존 V3의 매핑에서 일반 금액 한 칸만 변경한다.
        Path template = Path.of("../docs/reports/엑셀개인신청양식/v3/마블런2026_현장개인신청양식_V3.xlsx");
        List<OfflineRegistrationExcelRow> rows = new ArrayList<>();
        reader.readOfflineRegistrationWorkbook(new MockMultipartFile("file", Files.readAllBytes(template)),
                rows::addAll, ignored -> { });
        List<OfflineRegistrationExcelRow> modified = new ArrayList<>();
        for (OfflineRegistrationExcelRow row : rows) {
            Map<String, String> cells = new HashMap<>(row.cells());
            if (row.rowNumber() == 9) { cells.put("C", amount); }
            modified.add(new OfflineRegistrationExcelRow(row.rowNumber(), cells, row.formulaColumns()));
        }

        // 범위 위반은 저장 예외가 아닌 매핑 오류로 반환한다.
        OfflineRegistrationImportResult result = new OfflineRegistrationImportResult();
        OfflineRegistrationExcelMapping parsed = validator.parseOfflineMapping("marvelrun2026", modified, result);
        if (allowed) {
            assertThat(result.errors()).isEmpty();
            assertThat(parsed.prices().get("10km").adultAmount()).isEqualByComparingTo(amount);
        } else {
            assertThat(parsed).isNull();
            assertThat(result.errors()).extracting(OfflineRegistrationImportResponse.Failure::code)
                    .containsExactly("INVALID_MAPPING");
        }
    }

    /** 입력 수식은 계산하지 않고 수식 여부와 행 번호를 보존한다. */
    @Test
    void readsRowsAcrossBatchBoundaryAndPreservesFormulas() throws Exception {
        List<OfflineRegistrationExcelRow> rows = new ArrayList<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            workbook.createSheet("매핑").createRow(0).createCell(0).setCellValue("mapping");
            org.apache.poi.ss.usermodel.Sheet input = workbook.createSheet("입력");
            for (int index = 0; index < 205; index++) {
                input.createRow(index).createCell(1).setCellValue("20000101");
            }
            input.getRow(100).createCell(2).setCellFormula("1+1");
            workbook.write(bytes);
            reader.readOfflineRegistrationWorkbook(new MockMultipartFile("file", bytes.toByteArray()), ignored -> { }, rows::addAll);
        }
        assertThat(rows).hasSize(205);
        assertThat(rows.get(100).rowNumber()).isEqualTo(101);
        assertThat(rows.get(100).formulaColumns()).contains("C");
    }

    /** 어린이 기준일 양쪽과 승인시각 양 끝을 해석하여 매핑 금액을 선택한다. */
    @Test
    void validatesAdultAndChildAtBirthAndTimeBoundaries() {
        Event event = mock(Event.class);
        EventCategory category = mock(EventCategory.class);
        EventCategorySouvenir selected = mock(EventCategorySouvenir.class);
        Souvenir souvenir = mock(Souvenir.class);
        when(event.getId()).thenReturn("event");
        when(events.findById("event")).thenReturn(Optional.of(event));
        when(category.getId()).thenReturn("category");
        when(category.getEvent()).thenReturn(event);
        when(category.getIsActive()).thenReturn(true);
        when(categories.findById("category")).thenReturn(Optional.of(category));
        when(selected.getId()).thenReturn("mapping");
        when(selected.getSouvenir()).thenReturn(souvenir);
        when(souvenir.getId()).thenReturn("shirt");
        when(souvenir.getEvent()).thenReturn(event);
        when(souvenir.getIsActive()).thenReturn(true);
        when(mappings.findAllMappingsByCategoryId("category")).thenReturn(List.of(selected));
        when(loader.load("event", Set.of("category"), Set.of("mapping"))).thenReturn(
                new kr.co.teambrain.marvelrun.admin.event.command.application.context.RegistrationPolicyContext(
                        mock(kr.co.teambrain.marvelrun.admin.event.command.application.domain.policy.EventRegistrationPolicy.class),
                        Map.of(), Map.of("mapping", List.of())));
        Map<String, String> adult = validCells();
        adult.put("B", "20131031");
        Map<String, String> child = validCells();
        child.putAll(Map.of("B", "20131101", "J", "130", "K", "부", "L", "보호자", "M", "01087654321", "N", "Y", "S", "235959"));
        OfflineRegistrationImportResult result = new OfflineRegistrationImportResult();
        validator.validateOfflineRows(mapping(), LocalDate.of(2026, 10, 3), List.of(
                new OfflineRegistrationExcelRow(7, adult, Set.of()), new OfflineRegistrationExcelRow(8, child, Set.of())), result);
        assertThat(result.errors()).isEmpty();
        assertThat(result.contexts()).extracting(OfflineRegistrationContext::amount)
                .containsExactly(new BigDecimal("35000"), new BigDecimal("20000"));
        assertThat(result.contexts().get(0).approvedAt().toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(result.contexts().get(1).approvedAt().toLocalTime()).isEqualTo(LocalTime.of(23, 59, 59));
    }

    /** 파일 첫 오류 이후에도 마지막 행의 독립 오류를 수집한다. */
    @Test
    void collectsMultipleErrorsAndContinuesToLastRow() {
        OfflineRegistrationImportResult result = new OfflineRegistrationImportResult();
        Map<String, String> first = validCells();
        first.put("S", "246000");
        first.put("O", "N");
        Map<String, String> last = validCells();
        last.put("D", "010123456789");
        validator.validateOfflineRows(mapping(), LocalDate.of(2026, 10, 3), List.of(
                new OfflineRegistrationExcelRow(7, first, Set.of()),
                new OfflineRegistrationExcelRow(1006, last, Set.of())), result);

        assertThat(result.errors()).extracting(OfflineRegistrationImportResponse.Failure::rowNumber).contains(7, 1006);
        assertThat(result.errors()).extracting(OfflineRegistrationImportResponse.Failure::field)
                .contains("approvalTime", "essentialConsent", "D");
        assertThat(result.contexts()).isEmpty();
    }

    /** TEXT 경계는 한글 바이트 수로 판단하고 이후 행 검증도 계속한다. */
    @Test
    void validatesNoteTextByteLimitAndContinuesReadingRows() {
        // 21,845개의 한글은 UTF-8에서 정확히 TEXT 최대 바이트 수다.
        String boundary = "가".repeat(21_845);
        Map<String, String> allowed = validCells();
        allowed.put("T", boundary);
        allowed.put("S", "246000");
        Map<String, String> exceeded = validCells();
        exceeded.put("T", boundary + "가");
        Map<String, String> later = validCells();
        later.put("S", "246000");
        OfflineRegistrationImportResult result = new OfflineRegistrationImportResult();

        // 다른 오류와 함께 수집하되 허용 경계의 비고를 오류로 판정하지 않는다.
        validator.validateOfflineRows(mapping(), LocalDate.of(2026, 10, 3), List.of(
                new OfflineRegistrationExcelRow(7, allowed, Set.of()),
                new OfflineRegistrationExcelRow(8, exceeded, Set.of()),
                new OfflineRegistrationExcelRow(1006, later, Set.of())), result);
        assertThat(result.errors()).filteredOn(error -> "NOTE_TOO_LONG".equals(error.code()))
                .extracting(OfflineRegistrationImportResponse.Failure::rowNumber).containsExactly(8);
        assertThat(result.errors()).filteredOn(error -> "approvalTime".equals(error.field()))
                .extracting(OfflineRegistrationImportResponse.Failure::rowNumber).containsExactly(7, 1006);
        assertThat(result.contexts()).isEmpty();
    }

    /** DDL의 이름·주소·상세주소·보호자·이메일 길이 초과를 행 오류로 수집한다. */
    @Test
    void collectsVarcharLengthViolationsBeforePersistence() {
        Map<String, String> cells = validCells();
        cells.putAll(Map.of("C", "가".repeat(51), "F", "가".repeat(301), "G", "가".repeat(256),
                "K", "가".repeat(51), "L", "가".repeat(51), "Q", "a".repeat(256)));
        OfflineRegistrationImportResult result = new OfflineRegistrationImportResult();

        // 각 제한은 첫 실패에서 중단하지 않고 독립적으로 표시한다.
        validator.validateOfflineRows(mapping(), LocalDate.of(2026, 10, 3),
                List.of(new OfflineRegistrationExcelRow(7, cells, Set.of())), result);
        assertThat(result.errors()).filteredOn(error -> "INVALID_TEXT".equals(error.code()))
                .extracting(OfflineRegistrationImportResponse.Failure::field)
                .containsExactlyInAnyOrder("C", "F", "G", "K", "L", "Q");
        assertThat(result.contexts()).isEmpty();
    }

    /** 어린이 10km와 일반 참가자의 어린이 사이즈를 서버에서도 차단한다. */
    @Test
    void rejectsAgeCategoryAndSizeViolations() {
        OfflineRegistrationImportResult result = new OfflineRegistrationImportResult();
        Map<String, String> child = validCells();
        child.put("B", "20131101");
        child.put("H", "10km");
        Map<String, String> adult = validCells();
        adult.put("J", "130");
        validator.validateOfflineRows(mapping(), LocalDate.of(2026, 10, 3), List.of(
                new OfflineRegistrationExcelRow(7, child, Set.of()), new OfflineRegistrationExcelRow(8, adult, Set.of())), result);
        assertThat(result.errors()).extracting(OfflineRegistrationImportResponse.Failure::field)
                .contains("category", "guardianConsent", "size");
    }

    /** 취소 행과 기본값만 남은 빈 행은 저장하지 않는다. */
    @Test
    void excludesCanceledAndDefaultRows() {
        OfflineRegistrationImportResult result = new OfflineRegistrationImportResult();
        validator.validateOfflineRows(mapping(), LocalDate.of(2026, 10, 3), List.of(
                new OfflineRegistrationExcelRow(7, Map.of("R", "당일취소"), Set.of()),
                new OfflineRegistrationExcelRow(8, Map.of("G", "없음", "N", "N", "O", "N", "P", "N"), Set.of())), result);
        assertThat(result.errors()).isEmpty();
        assertThat(result.canceledCount()).isEqualTo(1);
    }

    /** 부족 자원을 공유하는 모든 행에 같은 필요·잔여 수량을 표시한다. */
    @Test
    void mapsCapacityShortageToAllAffectedRows() {
        CapacityRequirementResolver resolver = mock(CapacityRequirementResolver.class);
        RegistrationCapacityService capacity = mock(RegistrationCapacityService.class);
        List<Map<String, Integer>> quantities = List.of(Map.of("shirt", 1), Map.of("shirt", 1));
        when(resolver.resolveAll(eq("event"), anyList())).thenReturn(quantities);
        when(capacity.findRegistrationCapacityShortages("event", quantities, false))
                .thenReturn(List.of(new CapacityShortage("shirt", 2, 1)));
        OfflineRegistrationImportResult result = new OfflineRegistrationImportResult();
        new OfflineRegistrationCapacityService(resolver, capacity).collectOfflineCapacityErrors("event",
                List.of(context(7, "19900101"), context(8, "20131101")), result, false);
        assertThat(result.errors()).extracting(OfflineRegistrationImportResponse.Failure::rowNumber).containsExactly(7, 8);
    }

    /** 첫 배치와 마지막 배치의 같은 참가자도 전체 파일 중복으로 표시한다. */
    @Test
    void rejectsDuplicatesAcrossBatches() {
        OfflineRegistrationImportResult result = new OfflineRegistrationImportResult();
        validator.validateOfflineDuplicates("event", List.of(context(7, "19900101"), context(1006, "19900101")), result);
        assertThat(result.errors()).extracting(OfflineRegistrationImportResponse.Failure::rowNumber).containsExactly(7, 1006);
    }

    /** 외부 완료 원장은 PG 키 없이 금액·승인시각·두 마케팅 동의를 유지한다. */
    @Test
    void createsAdultAndChildExternalLedgersWithoutTossFields() {
        for (OfflineRegistrationContext context : List.of(context(7, "19900101"), context(8, "20131101"))) {
            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
            Registration registration = Registration.createOfflinePaidRegistration(mock(Event.class), mock(EventCategory.class),
                    context, encoder.encode(context.birth().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)), LocalDateTime.now());
            Payment payment = Payment.createOfflineCompletedPayment(registration, context.approvedAt());
            assertThat(registration.isExternalPayment()).isTrue();
            assertThat(registration.getTermsMarketingAgreed()).isEqualTo(registration.getTermsMarketingChannelAgreed());
            assertThat(payment.getAmount()).isEqualByComparingTo(context.amount());
            assertThat(payment.getApprovedAt()).isEqualTo(context.approvedAt());
            assertThat(payment.getPaymentKey()).isNull();
            assertThat(payment.getReceiptUrl()).isNull();
            assertThat(payment.getTossStatus()).isNull();
            assertThat(encoder.matches(context.birth().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE), registration.getPassword())).isTrue();
        }
    }

    /** 실제 V3에서 오류를 발견하면 저장 서비스와 비밀번호 해시 작업을 호출하지 않는다. */
    @Test
    void neverPersistsWorkbookWithRowErrors() throws Exception {
        Path template = Path.of("../docs/reports/엑셀개인신청양식/v3/마블런2026_현장개인신청양식_V3.xlsx");
        MockMultipartFile file;
        try (java.io.InputStream source = Files.newInputStream(template);
             XSSFWorkbook workbook = new XSSFWorkbook(source); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Row row = workbook.getSheet("입력").getRow(6);
            validCells().forEach((column, value) -> {
                int index = column.charAt(0) - 'A';
                row.getCell(index, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(value);
            });
            row.getCell(18).setCellValue("246000");
            workbook.write(bytes);
            file = new MockMultipartFile("file", bytes.toByteArray());
        }
        OfflineRegistrationCapacityService capacity = mock(OfflineRegistrationCapacityService.class);
        OfflineRegistrationPersistenceService persistence = mock(OfflineRegistrationPersistenceService.class);
        org.springframework.security.crypto.password.PasswordEncoder encoder = mock(org.springframework.security.crypto.password.PasswordEncoder.class);
        OfflineRegistrationImportService service = new OfflineRegistrationImportService(reader, validator, capacity, persistence, encoder);
        assertThatThrownBy(() -> service.importOfflinePaidRegistrations("marvelrun2026", LocalDate.of(2026, 10, 1), file))
                .isInstanceOf(OfflineRegistrationImportException.class);
        verifyNoInteractions(persistence, encoder);
    }

    /** Swagger와 같은 multipart 요청에서 승인일자를 전달하고 행별 오류 응답을 받는다. */
    @Test
    void returnsRowErrorsThroughMultipartEndpoint() throws Exception {
        OfflineRegistrationImportService service = mock(OfflineRegistrationImportService.class);
        when(service.importOfflinePaidRegistrations(eq("event"), eq(LocalDate.of(2026, 10, 3)), any()))
                .thenThrow(new OfflineRegistrationImportException(0, List.of(
                        new OfflineRegistrationImportResponse.Failure(8, "approvalTime", "REQUIRED", "승인시각이 필요합니다."))));
        MockMvcBuilders.standaloneSetup(new RegistrationCommandController(mock(RegistrationCommandService.class), service))
                .setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(multipart("/v1/admin/events/event/registrations/offline-payments/import")
                        .file(new MockMultipartFile("file", new byte[]{1})).param("paymentDate", "2026-10-03"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.savedCount").value(0))
                .andExpect(jsonPath("$.errors[0].rowNumber").value(8));
    }

    /** 별도 승인된 MySQL 테스트 DB에서만 실제 트랜잭션·정원 동시성을 검증한다. */
    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.Tag("offline-import-db")
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MARVELRUN_OFFLINE_DB_TEST", matches = "true")
    @org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest(showSql = false, properties = {
            "spring.datasource.url=${MARVELRUN_TEST_DB_URL}",
            "spring.datasource.username=${MARVELRUN_TEST_DB_USERNAME}",
            "spring.datasource.password=${MARVELRUN_TEST_DB_PASSWORD}",
            "spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=never",
            "spring.flyway.enabled=false", "spring.liquibase.enabled=false"})
    @org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @org.springframework.context.annotation.Import({OfflineRegistrationPersistenceService.class,
            RegistrationCapacityService.class, OfflineRegistrationCapacityService.class, CapacityRequirementResolver.class,
            CapacityHoldService.class, ReservationPaymentService.class, OfflineRegistrationImportValidator.class,
            RegistrationPolicyLoader.class, RegistrationPolicyValidator.class, DatabaseConfiguration.class})
    class DatabaseTransactions {
        @org.springframework.beans.factory.annotation.Autowired
        private OfflineRegistrationPersistenceService persistence;
        @org.springframework.beans.factory.annotation.Autowired
        private org.springframework.jdbc.core.JdbcTemplate jdbc;
        @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
        private ReservationPaymentService reservationPayments;
        private String eventId;
        private String categoryId;
        private String souvenirId;

        /** 기존 운영 데이터와 분리된 UUID fixture만 생성한다. */
        @org.junit.jupiter.api.BeforeEach
        void createDatabaseFixture() {
            eventId = UUID.randomUUID().toString();
            categoryId = UUID.randomUUID().toString();
            souvenirId = UUID.randomUUID().toString();
            LocalDateTime now = LocalDateTime.now();
            jdbc.update("""
                    insert into event(id,name_kr,start_date,region,host,organizer,event_status,visible_status,
                    regist_start_date,regist_deadline,payment_deadline,auto_max_regist,auto_start,auto_deadline,phone_auth_required)
                    values(?,?,?,?,?,?,'CLOSED','OPEN',?,?,?,true,true,true,false)
                    """, eventId, "offline test", now.plusDays(30), "test", "test", "test",
                    now.minusDays(20), now.minusDays(10), now.minusDays(5));
            jdbc.update("insert into event_category(id,event_id,amount,name,is_active,sort_order) values(?,?,70000,'test',true,0)", categoryId, eventId);
            jdbc.update("insert into souvenir(id,event_id,name,sizes,is_active,sort_order) values(?,?,'test','M',true,0)", souvenirId, eventId);
            String total = insertCapacity("EVENT_TOTAL", null, "");
            String category = insertCapacity("CATEGORY", null, "");
            insertCapacity("SOUVENIR", souvenirId, "M");
            jdbc.update("insert into capacity_category(id,capacity_id,event_category_id) values(?,?,?)", UUID.randomUUID().toString(), category, categoryId);
            assertThat(total).isNotBlank();
        }

        /** 현재 fixture의 참조 순서대로 제거하며 다른 대회의 데이터는 건드리지 않는다. */
        @org.junit.jupiter.api.AfterEach
        void removeDatabaseFixture() {
            if (eventId == null) { return; }
            jdbc.update("delete ri from reservation_item ri join reservation rv on rv.id=ri.reservation_id join registration r on r.id=rv.registration_id where r.event_id=?", eventId);
            jdbc.update("delete rv from reservation rv join registration r on r.id=rv.registration_id where r.event_id=?", eventId);
            jdbc.update("delete pa from payment_allocation pa join registration r on r.id=pa.registration_id where r.event_id=?", eventId);
            jdbc.update("delete p from payment p join registration r on r.id=p.registration_id where r.event_id=?", eventId);
            jdbc.update("delete from registration where event_id=?", eventId);
            jdbc.update("delete cc from capacity_category cc join capacity c on c.id=cc.capacity_id where c.event_id=?", eventId);
            jdbc.update("delete from capacity where event_id=?", eventId);
            jdbc.update("delete from souvenir where event_id=?", eventId);
            jdbc.update("delete from event_category where event_id=?", eventId);
            jdbc.update("delete from event where id=?", eventId);
        }

        /** 정상 완료 시 외부 원장과 확정 점유를 함께 저장하며 동일 참가자 재등록을 거절한다. */
        @Test
        void commitsLedgerAndCapacityTogetherAndRejectsReimport() {
            OfflineRegistrationImportResult result = databaseInput("participant");
            OfflineRegistrationImportResponse response = persistence.persistOfflinePaidRegistrations(eventId, result, Map.of(7, "encoded"), LocalDateTime.now());
            assertThat(response.savedCount()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from registration where event_id=? and external_payment=1 and status='CONFIRMED'", Integer.class, eventId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from payment p join registration r on r.id=p.registration_id where r.event_id=? and p.process_status='COMPLETED' and p.payment_key is null and p.toss_status is null", Integer.class, eventId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from capacity where event_id=? and held_count=0 and confirmed_count=1", Integer.class, eventId)).isEqualTo(3);
            // 생성일이 오늘이어도 외부 결제는 어제 승인일 보고서에 포함되어야 한다.
            LocalDate approvedDate = result.contexts().getFirst().approvedAt().toLocalDate();
            kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationDailyReportQueryRepository report =
                    new kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationDailyReportQueryRepository(
                            new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc));
            assertThat(report.findPaymentDailyCounts(eventId, approvedDate.atStartOfDay(), approvedDate.plusDays(1).atStartOfDay()))
                    .containsExactly(new kr.co.teambrain.marvelrun.admin.event.query.dto.PaymentDailyCountRow(approvedDate, 1));
            assertThatThrownBy(() -> persistence.persistOfflinePaidRegistrations(eventId, databaseInput("participant"), Map.of(7, "encoded"), LocalDateTime.now()))
                    .isInstanceOf(OfflineRegistrationImportException.class);
        }

        /** 정원 확보 뒤 발생한 실패도 먼저 flush한 신청과 결제까지 모두 롤백한다. */
        @Test
        void rollsBackAllWritesWhenReservationConfirmationFails() {
            doThrow(new kr.co.teambrain.marvelrun.admin.common.exception.CustomException(
                    kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode.RESERVATION_STATE_CONFLICT))
                    .when(reservationPayments).confirmRegistrationPayments(eq(eventId), anyList(), anyList(), anyList(), any());
            assertThatThrownBy(() -> persistence.persistOfflinePaidRegistrations(eventId, databaseInput("rollback"), Map.of(7, "encoded"), LocalDateTime.now()))
                    .isInstanceOf(kr.co.teambrain.marvelrun.admin.common.exception.CustomException.class);
            assertThat(jdbc.queryForObject("select count(*) from registration where event_id=?", Integer.class, eventId)).isZero();
            assertThat(jdbc.queryForObject("select sum(held_count+confirmed_count) from capacity where event_id=?", Integer.class, eventId)).isZero();
        }

        /** 마지막 한 자리를 두 트랜잭션이 동시에 신청해도 한 파일만 커밋한다. */
        @Test
        void concurrentImportsCannotExceedCapacity() throws Exception {
            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            try {
                List<java.util.concurrent.Future<Boolean>> futures = new ArrayList<>();
                for (String name : List.of("first", "second")) {
                    futures.add(executor.submit(() -> {
                        start.await();
                        try {
                            persistence.persistOfflinePaidRegistrations(eventId, databaseInput(name), Map.of(7, "encoded"), LocalDateTime.now());
                            return true;
                        } catch (OfflineRegistrationImportException exception) {
                            return false;
                        }
                    }));
                }
                start.countDown();
                int success = 0;
                for (java.util.concurrent.Future<Boolean> future : futures) {
                    if (future.get(30, java.util.concurrent.TimeUnit.SECONDS)) { success++; }
                }
                assertThat(success).isEqualTo(1);
                assertThat(jdbc.queryForObject("select count(*) from registration where event_id=?", Integer.class, eventId)).isEqualTo(1);
            } finally {
                executor.shutdownNow();
                executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        }

        /** 실제 FK에 대응하는 외부 결제 저장 입력을 구성한다. */
        private OfflineRegistrationImportResult databaseInput(String name) {
            OfflineRegistrationImportResult result = new OfflineRegistrationImportResult();
            result.addRegistrationContext(new OfflineRegistrationContext(7, name, LocalDate.of(1990, 1, 1),
                    "01012345678", GenderClass.M, "test", "없음", categoryId,
                    List.of(new SouvenirJson(souvenirId, "M")), "", "", "", false, false, "", "", new BigDecimal("35000"),
                    LocalDateTime.now().minusDays(1), false));
            return result;
        }

        /** 테스트 자원의 실제 카운터 한도를 한 자리로 제한한다. */
        private String insertCapacity(String type, String souvenir, String size) {
            String id = UUID.randomUUID().toString();
            jdbc.update("""
                    insert into capacity(id,event_id,type,resource_key,name,souvenir_id,size,limit_count,held_count,confirmed_count,active,created_at,updated_at)
                    values(?,?,?,?,?,?,?,1,0,0,true,?,?)
                    """, id, eventId, type, "TEST:" + id, "test", souvenir, size, LocalDateTime.now(), LocalDateTime.now());
            return id;
        }
    }

    /** JPA 테스트에서도 입력 형식 검증에 사용할 표준 Validator를 제공한다. */
    @org.springframework.boot.test.context.TestConfiguration
    static class DatabaseConfiguration {
        /** 운영과 같은 Bean Validation 규칙을 제공한다. */
        @org.springframework.context.annotation.Bean
        org.springframework.validation.beanvalidation.LocalValidatorFactoryBean offlineValidator() {
            return new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();
        }
    }

    /** 정상 형식 입력의 최소 집합을 제공한다. */
    private Map<String, String> validCells() {
        Map<String, String> cells = new HashMap<>();
        cells.putAll(Map.of("B", "19900101", "C", "테스트", "D", "01012345678", "E", "남성", "F", "테스트 주소",
                "G", "없음", "H", "5km", "J", "M", "N", "N", "O", "Y"));
        cells.putAll(Map.of("P", "N", "R", "결제완료", "S", "000000"));
        return cells;
    }

    /** 가격과 기준은 테스트용 매핑을 사용한다. */
    private OfflineRegistrationExcelMapping mapping() {
        return new OfflineRegistrationExcelMapping("event", LocalDate.of(2013, 11, 1), "shirt", "티셔츠",
                Map.of("5km", new OfflineRegistrationExcelMapping.Price("category", new BigDecimal("35000"), new BigDecimal("20000")),
                        "10km", new OfflineRegistrationExcelMapping.Price("category10", new BigDecimal("35000"), null)),
                Set.of("M"), Set.of("130", "150"));
    }

    /** 테스트용 검증 완료 입력을 생성한다. */
    private OfflineRegistrationContext context(int row, String birth) {
        LocalDate date = LocalDate.parse(birth, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        boolean child = !date.isBefore(LocalDate.of(2013, 11, 1));
        return new OfflineRegistrationContext(row, "테스트", date, "01012345678", GenderClass.M,
                "주소", "없음", "category", List.of(new SouvenirJson("shirt", child ? "130" : "M")),
                "부", "보호자", "01087654321", child, false, "", "", new BigDecimal(child ? "20000" : "35000"),
                LocalDateTime.of(2026, 10, 3, 0, 0), child);
    }
}
