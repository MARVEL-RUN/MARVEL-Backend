package kr.co.teambrain.marvelrun.admin.event.query.dto.report;

import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualRosterModels.AddressParts;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualRosterModels.PackingRow;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualRosterModels.PrintRow;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 인쇄·패킹 명단 엑셀의 열 순서, 문자열 셀, 행 구성을 확인한다. */
class VirtualRosterWriterTest {
    private final VirtualPrintListExcelWriter printWriter = new VirtualPrintListExcelWriter();
    private final VirtualPackingListExcelWriter packingWriter = new VirtualPackingListExcelWriter();

    /** 패키지 한 건은 인쇄 3행이고 머리글은 0행, 모든 셀은 문자열이다. */
    @Test
    void printListHasHeaderAndStringCells() throws IOException {
        List<PrintRow> rows = List.of(
                new PrintRow("00123", "홍길동", VirtualRosterModels.stripDistance("5km | 어벤저스"), "1차"),
                new PrintRow("20001", "홍길동", VirtualRosterModels.stripDistance("10km | 엑스맨"), "1차"),
                new PrintRow("", "홍길동", "가디언즈", "1차"));
        Sheet sheet = read(out -> printWriter.write(out, rows));

        assertThat(headers(sheet)).containsExactly("배번", "이름", "부문", "차수");
        assertThat(sheet.getLastRowNum()).isEqualTo(3);
        assertThat(values(sheet.getRow(1))).containsExactly("00123", "홍길동", "어벤저스", "1차");
        assertThat(values(sheet.getRow(2))).containsExactly("20001", "홍길동", "엑스맨", "1차");
        assertThat(values(sheet.getRow(3))).containsExactly("", "홍길동", "가디언즈", "1차");
        for (int r = 1; r <= 3; r++) {
            assertThat(sheet.getRow(r).getCell(0).getCellType()).isEqualTo(CellType.STRING);
        }
    }

    /** 패키지 한 건은 참가 항목 수만큼 패킹 행이고, 열은 고정이며 모든 셀은 문자열이다. */
    @Test
    void packingListHasOneRowPerEntry() throws IOException {
        List<PackingRow> rows = List.of(
                new PackingRow("reg-1", "1차", "패키지", "패키지 A", "어벤저스", "00001", "가", "티셔츠(L), 메달",
                        "신청자", "010-0000-0001", "01234", "서울시 어딘가", "101호"),
                new PackingRow("reg-1", "1차", "패키지", "패키지 A", "엑스맨", "", "나", "",
                        "신청자", "010-0000-0001", "01234", "서울시 어딘가", "101호"));
        Sheet sheet = read(out -> packingWriter.write(out, rows));

        assertThat(headers(sheet)).containsExactly("신청 번호", "차수", "상품", "종목", "부문", "배번", "이름",
                "기념품", "받는 사람", "연락처", "우편번호", "주소", "상세주소");
        assertThat(sheet.getLastRowNum()).isEqualTo(2);
        assertThat(values(sheet.getRow(1))).containsExactly("reg-1", "1차", "패키지", "패키지 A", "어벤저스", "00001", "가",
                "티셔츠(L), 메달", "신청자", "010-0000-0001", "01234", "서울시 어딘가", "101호");
        assertThat(values(sheet.getRow(2))).containsExactly("reg-1", "1차", "패키지", "패키지 A", "엑스맨", "", "나",
                "", "신청자", "010-0000-0001", "01234", "서울시 어딘가", "101호");
        List<String> cellTypes = new ArrayList<>();
        sheet.getRow(1).forEach(cell -> cellTypes.add(cell.getCellType().name()));
        assertThat(cellTypes).containsOnly("STRING");
    }

    /** 우편번호는 마지막 "_" 뒤 숫자 5자리일 때만 뗀다. */
    @Test
    void splitsZipcodeOnlyForFiveDigits() {
        assertThat(VirtualRosterModels.splitAddress("서울시 a_b_01234")).isEqualTo(new AddressParts("서울시 a_b", "01234"));
        assertThat(VirtualRosterModels.splitAddress("서울시_1234")).isEqualTo(new AddressParts("서울시_1234", ""));
        assertThat(VirtualRosterModels.splitAddress(null)).isEqualTo(new AddressParts("", ""));
    }

    /** 제어 문자는 지운다. */
    @Test
    void stripsControlCharacters() throws IOException {
        Sheet sheet = read(out -> printWriter.write(out, List.of(new PrintRow("1", "홍\u0001길동", "부문", "1차"))));
        assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("홍길동");
    }

    private interface Write { void write(ByteArrayOutputStream out) throws IOException; }

    private Sheet read(Write write) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write.write(out);
        return new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray())).getSheetAt(0);
    }

    private List<String> headers(Sheet sheet) { return values(sheet.getRow(0)); }

    private List<String> values(Row row) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < row.getLastCellNum(); i++) {
            values.add(row.getCell(i) == null ? "" : row.getCell(i).getStringCellValue());
        }
        return values;
    }
}
