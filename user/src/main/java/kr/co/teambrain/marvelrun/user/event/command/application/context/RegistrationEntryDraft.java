package kr.co.teambrain.marvelrun.user.event.command.application.context;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;

/** 검증을 마치고 저장을 기다리는 참가 항목 값. */
public record RegistrationEntryDraft(
        EventCategory eventCategory,
        String name,
        String phNum,
        String birth,
        GenderClass gender
) {
}
