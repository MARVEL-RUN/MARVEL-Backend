package kr.co.teambrain.marvelrun.admin.event.command.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 배번 발급 결과다. 건수는 참가 항목 기준이고 registrationsWithoutEntriesCount는 신청 기준이다. */
public record BibIssueResponse(
        @Schema(description = "이번에 발급한 참가 항목 수")
        int issuedCount,
        int alreadyIssuedCount,
        int externalPaymentSkippedCount,
        @Schema(description = "참가 항목이 빠진 신청 수(신청 기준). 참가 항목이 없는 구매 줄이 하나라도 있는 신청, 또는 상품 종목인데 구매 줄이 하나도 없는 신청")
        int registrationsWithoutEntriesCount,
        List<PerCategory> perCategory
) {
    /** 종목별 발급 건수와 번호 범위다. 발급이 없으면 번호는 null이다. */
    public record PerCategory(String eventCategoryId, String categoryName, int issuedCount,
            Integer firstNumber, Integer lastNumber) { }
}
