package kr.co.teambrain.marvelrun.admin.event.query.dto.report;

import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualRosterModels.Column;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualRosterModels.PackingRow;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** 패킹 업체용 명단을 한 시트에 쓴다. 참가 항목 하나가 한 행이며 같은 신청 번호의 행이 한 상자다. */
@Component
public class VirtualPackingListExcelWriter {
    private static final List<Column<PackingRow>> COLUMNS = List.of(
            new Column<>("신청 번호", PackingRow::registrationId),
            new Column<>("차수", PackingRow::roundLabel),
            new Column<>("상품", PackingRow::productLabel),
            new Column<>("종목", PackingRow::categoryName),
            new Column<>("부문", PackingRow::division),
            new Column<>("배번", PackingRow::bib),
            new Column<>("이름", PackingRow::name),
            new Column<>("기념품", PackingRow::souvenirs),
            new Column<>("받는 사람", PackingRow::receiver),
            new Column<>("연락처", PackingRow::phone),
            new Column<>("우편번호", PackingRow::zipcode),
            new Column<>("주소", PackingRow::address),
            new Column<>("상세주소", PackingRow::addressDetail));

    /** 행 순서는 호출자가 정한 그대로 쓴다. */
    public void write(OutputStream output, List<PackingRow> rows) throws IOException {
        VirtualRosterModels.writeSheet(output, "패킹명단", COLUMNS, rows);
    }
}
