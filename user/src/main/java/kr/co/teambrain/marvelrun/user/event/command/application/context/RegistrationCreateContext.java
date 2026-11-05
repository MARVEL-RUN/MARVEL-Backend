package kr.co.teambrain.marvelrun.user.event.command.application.context;

import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;

import java.util.List;

public record RegistrationCreateContext(

        Event event,

        EventCategory eventCategory,

        List<SouvenirJson> souvenirJsons,

        /** 저장할 참가 항목. 참가 항목이 없는 상품이면 빈 목록 */
        List<RegistrationEntryDraft> entries
) {
}