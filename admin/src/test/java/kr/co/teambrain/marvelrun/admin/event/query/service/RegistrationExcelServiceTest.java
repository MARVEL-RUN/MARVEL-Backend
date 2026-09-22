package kr.co.teambrain.marvelrun.admin.event.query.service;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationSearchCondition;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.SouvenirQueryRepository;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 생성 파일의 형식과 전화번호·수식 형태 문자열의 보존을 검증한다. DB는 사용하지 않는다. */
class RegistrationExcelServiceTest {

    /** 빈 검색 결과도 열 제목이 있는 정상 엑셀 파일로 내려준다. */
    @Test
    void emptyResultProducesReadableWorkbook() throws Exception {
        RegistrationQueryRepository repository = mock(RegistrationQueryRepository.class);
        SouvenirQueryRepository souvenirs = mock(SouvenirQueryRepository.class);
        when(repository.findAll(org.mockito.ArgumentMatchers.<Specification<Registration>>any(), any(Sort.class)))
                .thenReturn(List.of());
        RegistrationExcelService service = new RegistrationExcelService(repository, souvenirs);
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.download(new RegistrationSearchCondition("event", null, null, null, null), null, null, response);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertEquals("번호", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals(27, workbook.getSheetAt(0).getRow(0).getLastCellNum());
            assertEquals(0, workbook.getSheetAt(0).getLastRowNum());
        }
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertTrue(response.getHeader("Content-Disposition").contains("filename*=UTF-8''"));
    }

    /** 사용자 입력을 수식으로 실행하지 않으며 전화번호 앞자리 0을 보존한다. */
    @Test
    void userValuesRemainTextCells() throws Exception {
        RegistrationQueryRepository repository = mock(RegistrationQueryRepository.class);
        SouvenirQueryRepository souvenirs = mock(SouvenirQueryRepository.class);
        Registration registration = mock(Registration.class);
        when(registration.getName()).thenReturn("=1+1");
        when(registration.getPhNum()).thenReturn("01012345678");
        when(repository.findAll(org.mockito.ArgumentMatchers.<Specification<Registration>>any(), any(Sort.class)))
                .thenReturn(List.of(registration));
        RegistrationExcelService service = new RegistrationExcelService(repository, souvenirs);
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.download(new RegistrationSearchCondition("event", null, null, null, null), null, null, response);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertEquals(CellType.STRING, workbook.getSheetAt(0).getRow(1).getCell(3).getCellType());
            assertEquals("=1+1", workbook.getSheetAt(0).getRow(1).getCell(3).getStringCellValue());
            assertEquals("01012345678", workbook.getSheetAt(0).getRow(1).getCell(6).getStringCellValue());
        }
    }

    /** 빈 대회 ID로 전체 대회 개인정보가 조회되지 않도록 막는다. */
    @Test
    void blankEventIdDoesNotQueryRepository() {
        RegistrationQueryRepository repository = mock(RegistrationQueryRepository.class);
        SouvenirQueryRepository souvenirs = mock(SouvenirQueryRepository.class);
        RegistrationExcelService service = new RegistrationExcelService(repository, souvenirs);
        assertThrows(CustomException.class, () -> service.download(
                new RegistrationSearchCondition(" ", null, null, null, null),
                null, null, new MockHttpServletResponse()));
        verifyNoInteractions(repository, souvenirs);
    }
}
