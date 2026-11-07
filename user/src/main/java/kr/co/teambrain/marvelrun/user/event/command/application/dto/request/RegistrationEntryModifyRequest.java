package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;

import java.util.List;

/** 로그인한 참가자의 받는 사람 수정 요청. 목록에 담은 참가 항목만 바꾼다. */
public record RegistrationEntryModifyRequest(
        @NotEmpty
        List<@NotNull @Valid Entry> entries
) {
    /** 받는 사람 한 명. 값 제약은 신청 때 받는 사람(RegistrationEntryRequest)과 같다. */
    @Schema(name = "RegistrationEntryModifyRequestEntry")
    public record Entry(
            /** 신청 조회 응답의 참가 항목 ID */
            @NotBlank
            @Size(max = 40)
            String entryId,

            @NotBlank
            @Size(max = 50)
            String name,

            @NotBlank
            @Size(max = 14)
            String phNum,

            @NotBlank
            @Size(max = 10)
            String birth,

            @NotNull
            GenderClass gender
    ) {
    }
}
