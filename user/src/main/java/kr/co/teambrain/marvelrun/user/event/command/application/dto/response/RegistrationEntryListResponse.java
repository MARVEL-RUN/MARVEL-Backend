package kr.co.teambrain.marvelrun.user.event.command.application.dto.response;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationEntry;

import java.util.List;

/**
 * 로그인한 참가자 신청의 받는 사람 목록. 받는 사람 수정(PATCH)에 쓰는 entryId를 담는다.
 * 구매 줄 순서(itemSortOrder) → 종목 순서로 정렬한다.
 */
public record RegistrationEntryListResponse(List<Entry> entries) {

    public record Entry(
            String entryId,
            int itemSortOrder,
            String productId,
            String productName,
            String eventCategoryId,
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
