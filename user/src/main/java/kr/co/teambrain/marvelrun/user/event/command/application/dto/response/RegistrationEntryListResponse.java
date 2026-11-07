package kr.co.teambrain.marvelrun.user.event.command.application.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationEntry;

import java.util.List;

/**
 * 로그인한 참가자 신청의 받는 사람 목록. 받는 사람 수정(PATCH)에 쓰는 entryId를 담는다.
 * 구매 줄 순서(itemSortOrder) → 종목 순서로 정렬한다.
 */
public record RegistrationEntryListResponse(List<Entry> entries) {

    @Schema(name = "RegistrationEntryListEntry")
    public record Entry(
            @Schema(description = "받는 사람 수정에 쓰는 참가 항목 id")
            String entryId,
            @Schema(description = "구매 줄 순서")
            int itemSortOrder,
            @Schema(description = "구매 상품(단품·패키지) 종목 id")
            String productId,
            @Schema(description = "구매 상품(단품·패키지) 종목 이름")
            String productName,
            @Schema(description = "참가 종목 id")
            String eventCategoryId,
            @Schema(description = "참가 종목 이름")
            String categoryName,
            String name,
            String phNum,
            String birth,
            GenderClass gender
    ) {
        public static Entry from(RegistrationEntry entry) {
            return new Entry(entry.getId(), entry.getRegistrationItem().getSortOrder(),
                    entry.getRegistrationItem().getEventCategory().getId(),
                    entry.getRegistrationItem().getEventCategory().getName(),
                    entry.getEventCategory().getId(), entry.getEventCategory().getName(),
                    entry.getName(), entry.getPhNum(), entry.getBirth(), entry.getGender());
        }
    }
}
