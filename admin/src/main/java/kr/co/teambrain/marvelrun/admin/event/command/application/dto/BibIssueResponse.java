package kr.co.teambrain.marvelrun.admin.event.command.application.dto;

import java.util.List;

/** 배번 발급 결과다. 건수는 참가 항목 기준이고 registrationsWithoutEntriesCount는 신청 기준이다. */
public record BibIssueResponse(
        int issuedCount,
        int alreadyIssuedCount,
        int externalPaymentSkippedCount,
        int registrationsWithoutEntriesCount,
        List<PerCategory> perCategory
) {
    /** 종목별 발급 건수와 번호 범위다. 발급이 없으면 번호는 null이다. */
    public record PerCategory(String eventCategoryId, String categoryName, int issuedCount,
            Integer firstNumber, Integer lastNumber) { }
}
