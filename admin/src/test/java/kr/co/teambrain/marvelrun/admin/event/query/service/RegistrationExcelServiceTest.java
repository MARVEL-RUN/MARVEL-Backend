package kr.co.teambrain.marvelrun.admin.event.query.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationSearchCondition;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.SouvenirQueryRepository;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DB 접속 없이 다운로드 조회 조건과 실제 XLSX 셀 및 상태 직렬화 계약을 검증한다. */
class RegistrationExcelServiceTest {

    private final RegistrationQueryRepository repository = mock(RegistrationQueryRepository.class);
    private final RegistrationExcelService service = new RegistrationExcelService(
            repository, mock(SouvenirQueryRepository.class));

    /** 선택 ID 유무에 따른 삭제 필터와 대회·검색·단체 조건 및 정렬 계약을 검증한다. */
    @ParameterizedTest
    @CsvSource({"NULL, false", "EMPTY, false", "SELECTED, true"})
    @SuppressWarnings("unchecked")
    void preservesQueryScopeAndSort(String selectionMode, boolean includesDeleted) throws Exception {
        // 전체·빈 선택·명시적 선택에 동일한 검색 조건을 준비한다.
        List<String> ids = switch (selectionMode) {
            case "NULL" -> null;
            case "EMPTY" -> List.of();
            default -> List.of("selected-1", "selected-2");
        };
        RegistrationSearchCondition condition = new RegistrationSearchCondition(
                "event-1", "ORGANIZATION", "category-1", RegistrationStatus.CANCELED, "검색이름");
        when(repository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of());

        // 서비스가 저장소에 전달한 명세를 실제로 구성하여 조건을 확인한다.
        service.download(condition, "organization-1", ids, new MockHttpServletResponse());
        ArgumentCaptor<Specification<Registration>> specification = ArgumentCaptor.forClass(Specification.class);
        verify(repository).findAll(specification.capture(), org.mockito.ArgumentMatchers.eq(
                Sort.by(Sort.Order.desc("registrationDate"), Sort.Order.desc("id"))));
        Root<Registration> root = mock(Root.class, RETURNS_DEEP_STUBS);
        CriteriaQuery<Registration> query = mock(CriteriaQuery.class);
        CriteriaBuilder builder = mock(CriteriaBuilder.class, RETURNS_DEEP_STUBS);
        when(query.getResultType()).thenReturn(Registration.class);
        specification.getValue().toPredicate(root, query, builder);

        // 선택 다운로드도 기존 검색 범위를 유지하며 삭제 필터만 해제한다.
        Path<Object> eventId = root.join("event", JoinType.INNER).get("id");
        Path<Object> categoryId = root.join("eventCategory", JoinType.INNER).get("id");
        Path<Object> status = root.get("status");
        Path<Object> organization = root.get("organization");
        Path<Object> organizationId = organization.get("id");
        Path<String> name = root.get("name");
        Path<String> phone = root.get("phNum");
        Path<String> groupName = root.join("organization", JoinType.LEFT).get("groupName");
        Path<Boolean> softDeleted = root.get("softDeleted");
        verify(builder).equal(eventId, "event-1");
        verify(builder).equal(categoryId, "category-1");
        verify(builder).equal(status, RegistrationStatus.CANCELED);
        verify(builder).isNotNull(organization);
        verify(builder).equal(organizationId, "organization-1");
        verify(builder).like(name, "%검색이름%");
        verify(builder).like(phone, "%검색이름%");
        verify(builder).like(groupName, "%검색이름%");
        if (includesDeleted) {
            verify(builder, never()).isFalse(softDeleted);
            verify(root.get("id")).in(ids);
        } else {
            verify(builder).isFalse(softDeleted);
            verify(root, never()).get("id");
        }
    }

    /** 모든 상태의 한글 문구와 삭제 표시를 검증하고 동명이인 두 신청이 각각 출력되는지 확인한다. */
    @ParameterizedTest
    @CsvSource({
            "PENDING, 결제 대기(만료 제한 없음), false",
            "PAYMENT_PENDING, 최초 결제 대기, false",
            "CONFIRMED, 참가 확정, false",
            "ADDITIONAL_PAYMENT_REQUIRED, 추가 결제 필요, false",
            "PARTIAL_REFUND_REQUIRED, 부분 환불 필요, false",
            "CANCELLATION_PENDING, 신청 취소·환불 처리 중, true",
            "CANCELED, 신청 취소 완료, true",
            "EXPIRED, 미결제 만료, true"
    })
    @SuppressWarnings("unchecked")
    void writesKoreanStatusAndRosterExclusion(RegistrationStatus status, String label, boolean deleted)
            throws Exception {
        // 같은 이름의 두 신청을 저장소 반환 순서와 반대인 선택 순서로 요청한다.
        Registration first = Registration.builder().id("first").name("테스트참가자")
                .status(status).softDeleted(deleted).build();
        Registration second = Registration.builder().id("second").name("테스트참가자")
                .status(RegistrationStatus.PAYMENT_PENDING).softDeleted(false).build();
        when(repository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of(first, second));
        MockHttpServletResponse response = new MockHttpServletResponse();

        // 다운로드 응답으로 생성한 실제 XLSX를 다시 읽는다.
        service.download(new RegistrationSearchCondition("event-1", null, null, null, null),
                null, List.of("second", "first"), response);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            Sheet sheet = workbook.getSheet("신청목록");

            // 기존 열 위치와 조회 순서를 유지하고 마지막 열에 명단 제외 여부를 표시한다.
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short) 28);
            assertThat(sheet.getRow(0).getCell(16).getStringCellValue()).isEqualTo("신청상태");
            assertThat(sheet.getRow(0).getCell(27).getStringCellValue()).isEqualTo("현재 명단 제외 여부");
            assertThat(sheet.getRow(1).getCell(0).getNumericCellValue()).isEqualTo(1);
            assertThat(sheet.getRow(2).getCell(0).getNumericCellValue()).isEqualTo(2);
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("first");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("second");
            assertThat(sheet.getRow(1).getCell(16).getStringCellValue()).isEqualTo(label);
            assertThat(sheet.getRow(1).getCell(27).getStringCellValue()).isEqualTo(deleted ? "Y" : "N");
            assertThat(sheet.getRow(2).getCell(27).getStringCellValue()).isEqualTo("N");
        }
    }

    /** 한글 표시명 추가 후에도 API 직렬화와 영문 상태 식별자의 왕복 변환을 유지한다. */
    @ParameterizedTest
    @EnumSource(RegistrationStatus.class)
    void preservesEnglishStatusSerialization(RegistrationStatus status) throws Exception {
        // 기본 Jackson enum 계약과 저장 식별자 해석을 확인한다.
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(status);
        assertThat(json).isEqualTo("\"" + status.name() + "\"");
        assertThat(mapper.readValue(json, RegistrationStatus.class)).isEqualTo(status);
        assertThat(RegistrationStatus.valueOf(status.name())).isEqualTo(status);
    }

    /** 과거 데이터의 상태가 없으면 기존처럼 빈 셀을 출력한다. */
    @Test
    @SuppressWarnings("unchecked")
    void preservesBlankStatus() throws Exception {
        // 상태가 없는 신청 한 건을 준비한다.
        Registration registration = Registration.builder().id("legacy").status(null).build();
        when(repository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of(registration));
        MockHttpServletResponse response = new MockHttpServletResponse();

        // 누락 상태가 출력 오류나 임의 상태로 바뀌지 않는지 확인한다.
        service.download(new RegistrationSearchCondition("event-1", null, null, null, null),
                null, List.of("legacy"), response);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(16).getStringCellValue()).isEmpty();
        }
    }
}
