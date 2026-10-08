package kr.co.teambrain.marvelrun.admin.event.query.dto.report;

import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationDeliveryExcelRequest;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.RegistrationDeliveryReportModels.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 배송 명단 4종을 스트리밍으로 작성한다. 확정 여부 판정이나 데이터 조회는 수행하지 않는다. */
@Component
public class RegistrationDeliveryExcelWriter {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 요청마다 독립적인 워크북과 카운터를 준비한다. */
    public WorkbookSession openDeliveryWorkbook(EventInfo event, RegistrationDeliveryExcelRequest request, LocalDateTime now) {
        return new WorkbookSession(event,request,now);
    }

    /** 100행 창과 템플릿 상단을 분리하여 본문을 누적하지 않고 최종 인원수를 기록한다. */
    public static final class WorkbookSession implements AutoCloseable {
        private final XSSFWorkbook template = new XSSFWorkbook();
        private final SXSSFWorkbook workbook;
        private final Sheet[] sheets = new Sheet[4];
        private final int[] next = {7,7,7,7};
        private final int[] undecidable = new int[4];
        private final int[] unknownDates = new int[4];
        private final List<List<String>> headers = new ArrayList<>();
        private final CellStyle dateStyle;
        private final CellStyle textStyle;
        private final CellStyle headingStyle;

        /** 템플릿 상단은 메모리에 유지하고 본문만 SXSSF로 내보낸다. */
        private WorkbookSession(EventInfo event, RegistrationDeliveryExcelRequest request, LocalDateTime now) {
            String[] names = {"개인신청명단","단체신청명단","개인신청불명확명단","단체신청불명확명단"};
            headingStyle = template.createCellStyle();
            Font font = template.createFont(); font.setBold(true); headingStyle.setFont(font);
            headingStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            headingStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            textStyle = template.createCellStyle(); textStyle.setWrapText(true); textStyle.setVerticalAlignment(VerticalAlignment.TOP);
            dateStyle = template.createCellStyle(); dateStyle.setDataFormat(template.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));

            // 필터 위의 최대 6행에 대회·기간·생성 시각과 집계 공간을 배치한다.
            for (int i=0;i<4;i++) {
                Sheet sheet = template.createSheet(names[i]);
                List<String> columns = new ArrayList<>(List.of("이름","생년월일","전화번호"));
                if (i%2==1) { columns.add("단체명"); }
                columns.addAll(List.of("종목명","기념품명","기념품사이즈","주소","상세주소","최초 결제일시(KST)","신청일시(KST)","신청상태"));
                if (i>=2) {
                    columns.set(i%2==1 ? 4 : 3,"현재 신청 종목명");
                    columns.set(i%2==1 ? 5 : 4,"현재 신청 기념품명");
                    columns.set(i%2==1 ? 6 : 5,"현재 신청 기념품사이즈");
                    columns.addAll(List.of("최근 확정 종목명","최근 확정 기념품명","최근 확정 기념품사이즈","불명확 사유",
                            "최근 확정 정보 판별결과","확인 불가 사유","관리자 확인사항","변경·결제·환불 이력(KST)"));
                }
                headers.add(columns);
                top(sheet,0,event.name()+" / 대회 시작: "+format(event.startDate()));
                top(sheet,1,"조회 기간(KST): ["+format(request.startAt())+", "+format(request.endAt())+") · 시작 포함/종료 제외");
                top(sheet,2,"생성 시각(KST): "+format(now)+" · 조회 중 변경은 다음 다운로드에 반영");
                top(sheet,3,""); top(sheet,4,"최초 승인일 미확인 대상은 조회 기간 포함 여부를 판정할 수 없습니다.");
                top(sheet,5,i>=2 ? "현재 신청 정보는 미정산 선택값일 수 있습니다. 이력의 시간 순서만으로 수정과 결제의 연결을 확정할 수 없습니다. 요청 시점과 현재 상태를 구분해 확인하세요." : "미정산·확인 필요 대상은 불명확명단을 확인하세요.");
                Row header = sheet.createRow(6);
                for (int c=0;c<columns.size();c++) {
                    Cell cell=header.createCell(c); cell.setCellValue(columns.get(c)); cell.setCellStyle(headingStyle);
                    sheet.setColumnWidth(c,24*256);
                }
                if (i>=2) {
                    sheet.setColumnWidth(columns.size()-2,60*256);
                    sheet.setColumnWidth(columns.size()-1,80*256);
                    sheet.getRow(5).setHeightInPoints(32);
                }
                for (int r=0;r<6;r++) { sheet.addMergedRegion(new CellRangeAddress(r,r,0,columns.size()-1)); }
                sheet.createFreezePane(0,7);
            }
            workbook = new SXSSFWorkbook(template,100);
            workbook.setCompressTempFiles(true);
            for (int i=0;i<4;i++) { sheets[i]=workbook.getSheetAt(i); }
        }

        /** 판정된 한 신청을 정확히 한 시트에 기록한다. */
        public void appendDeliveryRow(ExportRow export) {
            if (export.classification().excluded()) { return; }
            Candidate row = export.candidate();
            int sheetIndex=(row.organizationId()==null ? 0 : 1)+(export.classification().unclear() ? 2 : 0);
            List<Object> values=new ArrayList<>(Arrays.asList(row.name(),row.birth(),row.phone()));
            if (row.organizationId()!=null) { values.add(row.groupName()); }
            values.addAll(Arrays.asList(export.current().category(),export.current().souvenirs(),export.current().sizes(),
                    row.address(),row.addressDetail(),row.firstApprovedUtc()==null ? null : row.firstApprovedUtc().plusHours(9),
                    row.registrationAt(),RegistrationStatus.valueOf(row.status()).getDisplayName()));
            if (sheetIndex>=2) {
                HistoryResult history=export.history();
                values.addAll(Arrays.asList(displaySelection(history.previous().category()),
                        displaySelection(history.previous().souvenirs()),displaySelection(history.previous().sizes()),
                        String.join(" / ",export.classification().reasons()),history.result(),history.reason(),
                        export.review().instructions()));
                if (!"확인 가능".equals(history.result())) { undecidable[sheetIndex]++; }
                // Excel의 셀 문자열 한도를 넘는 이력도 버리지 않고 계속 열로 나눈다.
                String text=clean(export.review().timeline());
                if (text.isEmpty()) { values.add(""); }
                for (int start=0;start<text.length();) {
                    int end=Math.min(start+30000,text.length());
                    if (end<text.length() && Character.isHighSurrogate(text.charAt(end-1))) { end--; }
                    values.add(text.substring(start,end)); start=end;
                }
            }
            if (row.firstApprovedUtc()==null) { unknownDates[sheetIndex]++; }
            Row output=sheets[sheetIndex].createRow(next[sheetIndex]++);
            int lineCount=1;
            for (int c=0;c<values.size();c++) {
                if (c>=headers.get(sheetIndex).size()) {
                    headers.get(sheetIndex).add("변경·결제·환불 이력 계속 "+(c+1));
                    Cell header=template.getSheetAt(sheetIndex).getRow(6).createCell(c);
                    header.setCellValue(headers.get(sheetIndex).get(c)); header.setCellStyle(headingStyle);
                    sheets[sheetIndex].setColumnWidth(c,50*256);
                }
                Cell cell=output.createCell(c);
                Object value=values.get(c);
                if (value instanceof LocalDateTime time) { cell.setCellValue(time); cell.setCellStyle(dateStyle); }
                else {
                    String text=value==null ? "" : clean(value.toString());
                    cell.setCellValue(text); cell.setCellStyle(textStyle);
                    lineCount=Math.max(lineCount,1+(int)text.chars().filter(character -> character=='\n').count());
                }
            }
            output.setHeightInPoints(Math.min(409,Math.max(18,lineCount*15)));
        }

        /** 본문 행이 플러시된 뒤에도 템플릿 상단에 실제 집계 수치를 기록한다. */
        public void writeDeliveryWorkbook(OutputStream output) throws IOException {
            for (int i=0;i<4;i++) {
                template.getSheetAt(i).getRow(3).getCell(0).setCellValue("시트 인원: "+(next[i]-7)
                        +" / 최근 확정 정보 일부·전체 판별 불가: "+undecidable[i]+" / 기간 판정 불가: "+unknownDates[i]);
                sheets[i].setAutoFilter(new CellRangeAddress(6,Math.max(6,next[i]-1),0,headers.get(i).size()-1));
            }
            workbook.write(output);
        }

        /** 상단 한 행의 텍스트를 준비한다. */
        private void top(Sheet sheet,int index,String text) {
            Cell cell=sheet.createRow(index).createCell(0); cell.setCellValue(clean(text)); cell.setCellStyle(textStyle);
        }

        /** 정상·예외 경로 모두에서 SXSSF 임시 파일을 정리한다. */
        @Override public void close() throws IOException {
            try { workbook.close(); } finally { workbook.dispose(); }
        }
    }

    /** 복원된 명칭을 훼손하지 않고 판별 실패 항목만 관리자용 표기로 바꾼다. */
    private static String displaySelection(String value) {
        return Arrays.stream(value.split("\\n",-1))
                .map(part -> "판별 불가".equals(part) ? "확인 불가" : part)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /** 안내용 일시는 초까지 명시한다. */
    private static String format(LocalDateTime value) { return value==null ? "확인 불가" : value.format(TIME); }

    /** XML에서 허용하지 않는 제어문자를 제거하되 줄바꿈과 문자열 셀을 유지한다. */
    private static String clean(String value) { return value==null ? "" : value.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", ""); }
}
