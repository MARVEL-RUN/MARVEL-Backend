package kr.co.teambrain.marvelrun.admin.event.query.dto.response;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;

/** 신청의 종목별 참가 항목. 배송지는 신청 주소 하나를 쓴다. 배번은 발급 전에는 비어 있다. */
public record RegistrationEntryResponse(
        String entryId,
        String eventCategoryId,
        String eventCategoryName,
        String name,
        String phNum,
        String birth,
        GenderClass gender,
        String bibNumber,
        /** 참가 항목이 속한 구매 줄 ID와 그 상품명. 한 주문에 상품이 여럿이면 줄을 가리킨다. */
        String itemId,
        String productName
) {
    public static RegistrationEntryResponse from(RegistrationEntry entry) {
        return new RegistrationEntryResponse(entry.getId(), entry.getEventCategory().getId(),
                entry.getEventCategory().getName(), entry.getName(), entry.getPhNum(), entry.getBirth(),
                entry.getGender(), entry.getBibNumber(), entry.getRegistrationItem().getId(),
                entry.getRegistrationItem().getEventCategory().getName());
    }
}
