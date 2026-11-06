package kr.co.teambrain.marvelrun.user.event.command.application.context;

import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;

import java.util.List;

/** 검증을 마치고 저장을 기다리는 구매 줄. 요청 순서가 신청 안 순서다. */
public record RegistrationItemDraft(
        EventCategory product,
        List<SouvenirJson> souvenirs,
        List<RegistrationEntryDraft> entries
) {
}
