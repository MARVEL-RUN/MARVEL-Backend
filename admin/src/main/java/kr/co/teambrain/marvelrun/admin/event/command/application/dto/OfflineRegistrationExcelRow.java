package kr.co.teambrain.marvelrun.admin.event.command.application.dto;

import java.util.Map;
import java.util.Set;

/** 원본 행 번호와 셀 문자열을 보존하며 수식 셀을 일반 입력과 구분한다. */
public record OfflineRegistrationExcelRow(int rowNumber, Map<String, String> cells,
                                          Set<String> formulaColumns) {
    /** 호출자가 배치 처리 이후 원본 데이터를 변경하지 못하도록 복사한다. */
    public OfflineRegistrationExcelRow {
        cells = Map.copyOf(cells);
        formulaColumns = Set.copyOf(formulaColumns);
    }

    /** 존재하지 않는 셀도 빈 문자열로 반환한다. */
    public String cellValue(String column) {
        return cells.getOrDefault(column, "");
    }
}
