package kr.co.teambrain.marvelrun.admin.event.query.dto.report;

import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualRosterModels.Column;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualRosterModels.PrintRow;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** 인쇄 업체용 명단을 한 시트에 쓴다. 머리글은 0행이며 모든 값은 문자열 셀이다. */
@Component
public class VirtualPrintListExcelWriter {
    static final List<Column<PrintRow>> COLUMNS = List.of(
            new Column<>("배번", PrintRow::bib),
            new Column<>("이름", PrintRow::name),
            new Column<>("부문", PrintRow::categoryName),
            new Column<>("차수", PrintRow::roundLabel));

    /** 행 순서는 호출자가 정한 그대로 쓴다. */
    public void write(OutputStream output, List<PrintRow> rows) throws IOException {
        VirtualRosterModels.writeSheet(output, "인쇄명단", COLUMNS, rows);
    }
}
