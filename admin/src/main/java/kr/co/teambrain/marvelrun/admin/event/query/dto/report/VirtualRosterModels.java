package kr.co.teambrain.marvelrun.admin.event.query.dto.report;

import java.util.List;
import java.util.regex.Pattern;

/** 버추얼런 인쇄·패킹 명단이 함께 쓰는 행 모델과 문자열 규칙이다. */
public final class VirtualRosterModels {
    /** 정적 이름 공간이며 인스턴스를 생성하지 않는다. */
    private VirtualRosterModels() { }

    /** 종목명 앞에 붙은 "5km | " 같은 거리 접두어다. */
    private static final Pattern DISTANCE_PREFIX = Pattern.compile("^\\s*\\d[\\d.,]*\\s*[A-Za-z]*\\s*\\|\\s*");
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]");

    /** 인쇄 업체용 한 행이며 참가 항목 하나다. 부문은 거리 접두어를 뗀 종목명이다. */
    public record PrintRow(String bib, String name, String categoryName, String roundLabel) { }

    /**
     * 패킹 업체용 한 행이며 참가 항목 하나다. 같은 신청 번호의 행이 한 상자다.
     * 상품·종목은 구매 줄 값, 부문은 거리 접두어를 뗀 항목 종목명이다. 기념품은 구매 줄의 첫 행에만 적는다.
     */
    public record PackingRow(String registrationId, String roundLabel, String productLabel, String categoryName,
            String division, String bib, String name, String souvenirs, String receiver, String phone,
            String zipcode, String address, String addressDetail) { }

    /** 주소를 "주소"와 "우편번호"로 나눈 결과다. */
    public record AddressParts(String address, String zipcode) { }

    /** 종목명 앞의 거리 접두어를 떼고 나머지를 돌려준다. */
    public static String stripDistance(String categoryName) {
        return categoryName == null ? "" : DISTANCE_PREFIX.matcher(categoryName).replaceFirst("");
    }

    /** "주소_우편번호" 꼴에서 마지막 "_" 뒤가 숫자 5자리일 때만 우편번호를 뗀다. */
    public static AddressParts splitAddress(String address) {
        if (address != null) {
            int separator = address.lastIndexOf('_');
            if (separator >= 0 && address.substring(separator + 1).matches("\\d{5}")) {
                return new AddressParts(address.substring(0, separator), address.substring(separator + 1));
            }
        }
        return new AddressParts(address == null ? "" : address, "");
    }

    /** XML 제어 문자를 제거하고 엑셀 셀의 최대 문자 수를 지킨다. */
    public static String clean(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = CONTROL_CHARACTERS.matcher(value).replaceAll("");
        return cleaned.length() > 32767 ? cleaned.substring(0, 32767) : cleaned;
    }

    /** 열 머리글과 값을 꺼내는 방법을 한 쌍으로 묶는다. */
    public record Column<T>(String header, java.util.function.Function<T, String> value) { }

    /** 머리글을 0행에 쓰고 모든 값을 문자열 셀로 쓴다. */
    static <T> void writeSheet(java.io.OutputStream output, String sheetName, List<Column<T>> columns, List<T> rows)
            throws java.io.IOException {
        org.apache.poi.xssf.streaming.SXSSFWorkbook workbook = new org.apache.poi.xssf.streaming.SXSSFWorkbook(100);
        try {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet(sheetName);
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                header.createCell(i).setCellValue(clean(columns.get(i).header()));
            }
            int rowIndex = 1;
            for (T row : rows) {
                org.apache.poi.ss.usermodel.Row line = sheet.createRow(rowIndex++);
                for (int i = 0; i < columns.size(); i++) {
                    line.createCell(i, org.apache.poi.ss.usermodel.CellType.STRING)
                            .setCellValue(clean(columns.get(i).value().apply(row)));
                }
            }
            workbook.write(output);
        } finally {
            workbook.dispose();
            workbook.close();
        }
    }
}
