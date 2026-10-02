package kr.co.teambrain.marvelrun.admin.event.command.application.excel;

import kr.co.teambrain.marvelrun.admin.event.command.application.dto.OfflineRegistrationExcelRow;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.OfflineRegistrationImportResponse.Failure;
import kr.co.teambrain.marvelrun.admin.event.command.application.exception.OfflineRegistrationImportException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.SharedStrings;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** V3 셀 XML을 순차 해석하며 수식 계산 없이 원본 값을 100행씩 전달한다. */
@Component
public class OfflineRegistrationExcelReader {
    public static final int BATCH_SIZE = 100;
    public static final int FIRST_ROW = 7;
    public static final int LAST_ROW = 1006;

    /** 매핑을 먼저 전달한 뒤 입력 행을 배치로 읽으며 업무 오류 처리는 호출부에 맡긴다. */
    public void readOfflineRegistrationWorkbook(MultipartFile file,
            Consumer<List<OfflineRegistrationExcelRow>> mappingConsumer,
            Consumer<List<OfflineRegistrationExcelRow>> rowConsumer) {
        // ZIP 전체 해제나 Workbook 생성을 피하고 필요한 시트만 순차 해석한다.
        if (file == null || file.isEmpty()) {
            throw fileError("EMPTY_FILE", "업로드할 xlsx 파일이 필요합니다.");
        }
        Path temporaryFile = null;
        try {
            temporaryFile = Files.createTempFile("offline-registration-", ".xlsx");
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, temporaryFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try (OPCPackage archive = OPCPackage.open(temporaryFile.toFile(), PackageAccess.READ)) {
                XSSFReader reader = new XSSFReader(archive);
                SharedStrings strings = reader.getSharedStringsTable();
                readNamedSheet(reader, strings, "매핑", mappingConsumer);
                readNamedSheet(reader, strings, "입력", rowConsumer);
            }
        } catch (OfflineRegistrationImportException exception) {
            throw exception;
        } catch (org.springframework.dao.DataAccessException exception) {
            throw exception;
        } catch (kr.co.teambrain.marvelrun.admin.common.exception.CustomException exception) {
            throw exception;
        } catch (Exception exception) {
            // 파일 내부 경로나 셀 원문을 외부 오류에 노출하지 않는다.
            throw fileError("INVALID_WORKBOOK", "xlsx 파일 또는 필수 시트 구조를 읽을 수 없습니다.");
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (java.io.IOException exception) {
                    // 삭제 실패는 별도 로그로 추적하되 원래 검증 결과를 덮어쓰지 않는다.
                    org.slf4j.LoggerFactory.getLogger(OfflineRegistrationExcelReader.class)
                            .warn("외부 신청 임시 파일 삭제 실패: {}", temporaryFile.getFileName());
                }
            }
        }
    }

    /** 시트 이름으로 관계를 찾아 XML 행을 해석하며 동일 이름의 중복을 거절한다. */
    private void readNamedSheet(XSSFReader reader, SharedStrings strings, String sheetName,
            Consumer<List<OfflineRegistrationExcelRow>> consumer) throws Exception {
        XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
        boolean found = false;
        while (sheets.hasNext()) {
            try (InputStream sheet = sheets.next()) {
                if (!sheetName.equals(sheets.getSheetName())) {
                    continue;
                }
                if (found) {
                    throw fileError("DUPLICATE_SHEET", "같은 이름의 필수 시트가 중복되었습니다.");
                }
                found = true;

                // POI 보안 설정을 적용한 SAX 파서로 외부 엔티티 해석을 방지한다.
                XMLReader parser = XMLHelper.newXMLReader();
                SheetHandler handler = new SheetHandler(strings, consumer);
                parser.setContentHandler(handler);
                parser.parse(new InputSource(sheet));
                handler.deliverRemainingRows();
            }
        }
        if (!found) {
            throw fileError("MISSING_SHEET", sheetName + " 시트가 없습니다.");
        }
    }

    /** 파일 오류는 행 번호 0으로 구분한다. */
    private static OfflineRegistrationImportException fileError(String code, String message) {
        return new OfflineRegistrationImportException(0,
                List.of(new Failure(0, "file", code, message)));
    }

    /** 엑셀 숫자·문자열·인라인 문자열을 읽으며 수식은 계산하지 않는다. */
    private static final class SheetHandler extends DefaultHandler {
        private final SharedStrings sharedStrings;
        private final Consumer<List<OfflineRegistrationExcelRow>> consumer;
        private final List<OfflineRegistrationExcelRow> batch = new ArrayList<>(BATCH_SIZE);
        private final Map<String, String> cells = new HashMap<>();
        private final Set<String> formulas = new HashSet<>();
        private final StringBuilder value = new StringBuilder();
        private String column;
        private String type;
        private int row;
        private int previousRow;
        private boolean capture;

        /** 공유 문자열과 행 수신자를 현재 파일 범위에서만 보관한다. */
        private SheetHandler(SharedStrings sharedStrings,
                Consumer<List<OfflineRegistrationExcelRow>> consumer) {
            this.sharedStrings = sharedStrings;
            this.consumer = consumer;
        }

        /** 행과 셀의 위치를 확인하고 값 읽기를 시작한다. */
        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes)
                throws SAXException {
            switch (localName) {
                case "row" -> {
                    row = Integer.parseInt(attributes.getValue("r"));
                    if (row <= previousRow) {
                        throw new SAXException("행 순서가 올바르지 않습니다.");
                    }
                    previousRow = row;
                    cells.clear();
                    formulas.clear();
                }
                case "c" -> {
                    String reference = attributes.getValue("r");
                    if (reference == null || !reference.matches("[A-Z]+" + row)) {
                        throw new SAXException("셀 위치가 올바르지 않습니다.");
                    }
                    column = reference.replaceAll("[0-9]", "");
                    type = attributes.getValue("t");
                    value.setLength(0);
                }
                case "f" -> formulas.add(column);
                case "v", "t" -> capture = true;
                default -> { }
            }
        }

        /** 문자열의 여러 SAX 조각을 원래 순서대로 연결한다. */
        @Override
        public void characters(char[] characters, int start, int length) {
            if (capture) {
                value.append(characters, start, length);
            }
        }

        /** 셀을 확정하고 100행이 모이면 불변 배치를 전달한다. */
        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            switch (localName) {
                case "v", "t" -> capture = false;
                case "c" -> {
                    String text = value.toString();
                    if ("s".equals(type) && !text.isEmpty()) {
                        text = sharedStrings.getItemAt(Integer.parseInt(text)).getString();
                    } else if ((type == null || "n".equals(type)) && !text.isEmpty()) {
                        text = new java.math.BigDecimal(text).stripTrailingZeros().toPlainString();
                    }
                    if (cells.putIfAbsent(column, text) != null) {
                        throw new SAXException("같은 셀이 중복되었습니다.");
                    }
                }
                case "row" -> {
                    batch.add(new OfflineRegistrationExcelRow(row, cells, formulas));
                    if (batch.size() == BATCH_SIZE) {
                        deliverRemainingRows();
                    }
                }
                default -> { }
            }
        }

        /** 마지막 100행 미만의 데이터도 누락 없이 전달한다. */
        private void deliverRemainingRows() {
            if (!batch.isEmpty()) {
                consumer.accept(List.copyOf(batch));
                batch.clear();
            }
        }
    }
}
