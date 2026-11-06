package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;

/** 주문자 정보 일괄 변경. 길이 제한은 개인 신청과 같다. 이 주문자의 모든 주문에 반영한다. */
public record ParticipantOrdererModifyRequest(
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Size(max = 14) String phNum,
        @NotBlank @Size(max = 10) String birth,
        @NotNull GenderClass gender
) {
}
