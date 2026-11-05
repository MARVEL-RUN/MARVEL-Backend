package kr.co.teambrain.marvelrun.admin.event.query.dto.response;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;

import java.time.LocalDateTime;
import java.util.List;

/** 신청의 참가 항목 조회 결과. editable은 받는 사람 수정 API를 쓸 수 있는 신청(패키지)일 때만 true다. */
public record RegistrationEntryListResponse(
        String registrationId,
        ProductType productType,
        boolean editable,
        List<Entry> entries
) {

    public record Entry(
            String entryId,
            String eventCategoryId,
            String categoryName,
            String name,
            String phNum,
            String birth,
            GenderClass gender,
            String bibNumber,
            LocalDateTime bibIssuedAt
    ) {
        public static Entry from(RegistrationEntry entry) {
            return new Entry(entry.getId(), entry.getEventCategory().getId(), entry.getEventCategory().getName(),
                    entry.getName(), entry.getPhNum(), entry.getBirth(), entry.getGender(),
                    entry.getBibNumber(), entry.getBibIssuedAt());
        }
    }
}
