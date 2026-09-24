package kr.co.teambrain.marvelrun.admin.event.query.dto.report;

/** query parameter로 다운로드할 당일·누계 시트를 선택한다. */
public enum ReportExcelMode {
    /** 날짜별 당일 표만 제공한다. */
    DAILY,
    /** 접수 시작부터 각 날짜까지 누계 표만 제공한다. */
    CUMULATIVE,
    /** 당일과 누계 시트를 함께 제공한다. */
    BOTH
}
