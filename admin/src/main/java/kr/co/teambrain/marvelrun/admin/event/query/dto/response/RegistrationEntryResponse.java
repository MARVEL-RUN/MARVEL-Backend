package kr.co.teambrain.marvelrun.admin.event.query.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;

/** 신청의 종목별 참가 항목. 배송지는 신청 주소 하나를 쓴다. 배번은 발급 전에는 비어 있다. */
public record RegistrationEntryResponse(
        @Schema(description = "참가 항목 id. 받는 사람 수정에 쓴다") String entryId,
        @Schema(description = "참가 종목 id") String eventCategoryId,
        @Schema(description = "참가 종목 이름") String eventCategoryName,
        String name,
        String phNum,
        String birth,
        GenderClass gender,
        @Schema(description = "배번. 발급 전이면 null") String bibNumber,
        /** 참가 항목이 속한 구매 줄 ID와 그 상품명. 한 주문에 상품이 여럿이면 줄을 가리킨다. */
        @Schema(description = "참가 항목이 속한 구매 줄 id. 상세 items[].itemId와 이어진다") String itemId,
        @Schema(description = "참가 항목이 속한 구매 줄의 상품(단품·패키지) 이름") String productName
) {
    public static RegistrationEntryResponse from(RegistrationEntry entry) {
        return new RegistrationEntryResponse(entry.getId(), entry.getEventCategory().getId(),
                entry.getEventCategory().getName(), entry.getName(), entry.getPhNum(), entry.getBirth(),
                entry.getGender(), entry.getBibNumber(), entry.getRegistrationItem().getId(),
                entry.getRegistrationItem().getEventCategory().getName());
    }
}
