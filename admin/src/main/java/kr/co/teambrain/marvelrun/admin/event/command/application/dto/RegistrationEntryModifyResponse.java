package kr.co.teambrain.marvelrun.admin.event.command.application.dto;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;

import java.util.List;

/** 관리자 패키지 받는 사람 수정 결과. 수정한 참가 항목만 담는다. */
public record RegistrationEntryModifyResponse(List<Entry> entries) {

    public record Entry(
            String entryId,
            String eventCategoryId,
            String categoryName,
            String name,
            String phNum,
            String birth,
            GenderClass gender,
            String bibNumber,
            String itemId,
            String productName
    ) {
        public static Entry from(RegistrationEntry entry) {
            return new Entry(entry.getId(), entry.getEventCategory().getId(), entry.getEventCategory().getName(),
                    entry.getName(), entry.getPhNum(), entry.getBirth(), entry.getGender(), entry.getBibNumber(),
                    entry.getRegistrationItem().getId(), entry.getRegistrationItem().getEventCategory().getName());
        }
    }
}
