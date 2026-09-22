package kr.co.teambrain.marvelrun.admin.event.query.dto;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;

public record RegistrationStatDto(
        RegistrationStatus status,
        String organizationId,
        GenderClass gender,
        String birth
) {
}