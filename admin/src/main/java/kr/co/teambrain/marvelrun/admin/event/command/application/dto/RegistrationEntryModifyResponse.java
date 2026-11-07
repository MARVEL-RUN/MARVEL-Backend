package kr.co.teambrain.marvelrun.admin.event.command.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;

import java.util.List;

/** 관리자 패키지 받는 사람 수정 결과. 수정한 참가 항목만 담는다. */
public record RegistrationEntryModifyResponse(List<Entry> entries) {

    @Schema(name = "RegistrationEntryModifyResultEntry")
    public record Entry(
            @Schema(description = "수정한 참가 항목 id")
            String entryId,
            @Schema(description = "참가 종목 id")
            String eventCategoryId,
            @Schema(description = "참가 종목 이름")
            String categoryName,
            String name,
            String phNum,
            String birth,
            GenderClass gender,
            @Schema(description = "배번. 발급 전이면 null")
            String bibNumber,
            @Schema(description = "참가 항목이 속한 구매 줄 id")
            String itemId,
            @Schema(description = "참가 항목이 속한 구매 줄의 상품(단품·패키지) 이름")
            String productName
    ) {
        public static Entry from(RegistrationEntry entry) {
            return new Entry(entry.getId(), entry.getEventCategory().getId(), entry.getEventCategory().getName(),
                    entry.getName(), entry.getPhNum(), entry.getBirth(), entry.getGender(), entry.getBibNumber(),
                    entry.getRegistrationItem().getId(), entry.getRegistrationItem().getEventCategory().getName());
        }
    }
}
