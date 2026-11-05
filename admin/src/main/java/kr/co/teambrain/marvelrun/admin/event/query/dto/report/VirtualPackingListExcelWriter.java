package kr.co.teambrain.marvelrun.admin.event.query.dto.report;

import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualRosterModels.Column;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualRosterModels.Course;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualRosterModels.EntryCell;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualRosterModels.PackingRow;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** 패킹 업체용 명단을 한 시트에 쓴다. 신청 하나가 한 행이며 하위 종목마다 배번·이름 열 쌍이 붙는다. */
@Component
public class VirtualPackingListExcelWriter {
    private static final List<Column<PackingRow>> HEAD = List.of(
            new Column<>("신청 번호", PackingRow::registrationId),
            new Column<>("차수", PackingRow::roundLabel),
            new Column<>("상품", PackingRow::productLabel),
            new Column<>("종목", PackingRow::categoryName));
    private static final List<Column<PackingRow>> TAIL = List.of(
            new Column<>("기념품", PackingRow::souvenirs),
            new Column<>("받는 사람", PackingRow::receiver),
            new Column<>("연락처", PackingRow::phone),
            new Column<>("우편번호", PackingRow::zipcode),
            new Column<>("주소", PackingRow::address),
            new Column<>("상세주소", PackingRow::addressDetail));

    /** 열 구성은 상품 열, 하위 종목별 배번·이름 열, 배송 열 순서다. */
    public List<Column<PackingRow>> columns(List<Course> courses) {
        List<Column<PackingRow>> columns = new ArrayList<>(HEAD);
        for (Course course : courses) {
            String name = VirtualRosterModels.stripDistance(course.name());
            columns.add(new Column<>(name + " 배번", row -> cell(row, course).bib()));
            columns.add(new Column<>(name + " 이름", row -> cell(row, course).name()));
        }
        columns.addAll(TAIL);
        return columns;
    }

    /** 해당 종목 항목이 없으면 빈 칸이다. */
    private static EntryCell cell(PackingRow row, Course course) {
        EntryCell cell = row.entries().get(course.categoryId());
        return cell == null ? new EntryCell("", "") : cell;
    }

    /** 행 순서는 호출자가 정한 그대로 쓴다. */
    public void write(OutputStream output, List<Course> courses, List<PackingRow> rows) throws IOException {
        VirtualRosterModels.writeSheet(output, "패킹명단", columns(courses), rows);
    }
}
