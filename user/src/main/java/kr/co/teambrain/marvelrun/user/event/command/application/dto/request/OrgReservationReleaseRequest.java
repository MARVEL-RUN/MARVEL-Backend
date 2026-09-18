package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;


import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 단체의 본인 확인 정보와 확보를 반환할 참가자 목록을 전달한다.
 *
 * 참가자들이 해당 단체에 속하는지는 서비스에서 검증한다.
 */
public record OrgReservationReleaseRequest(
        @NotNull @Valid OrganizationAccessRequest access,
        @NotEmpty List<@NotBlank String> registrationIds
) {
}