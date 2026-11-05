package kr.co.teambrain.marvelrun.admin.event.command.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;

import java.util.List;

/** 관리자 패키지 받는 사람 수정 요청. 목록에 담긴 참가 항목만 바꾼다. */
public record RegistrationEntryModifyRequest(
        @Valid
        @NotEmpty(message = "수정할 받는 사람을 입력해주세요.")
        List<@NotNull(message = "수정할 받는 사람을 입력해주세요.") Entry> entries
) {
    public record Entry(
            @NotBlank(message = "참가 항목 ID를 입력해주세요.")
            String entryId,

            @NotBlank(message = "이름을 입력해주세요.")
            String name,

            @NotBlank(message = "연락처를 입력해주세요.")
            @Pattern(regexp = "^[0-9]+$", message = "연락처는 '-' 없이 숫자만 입력해주세요.")
            String phNum,

            @NotBlank(message = "생년월일을 입력해주세요.")
            @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "생년월일은 'YYYY-MM-DD' 형식(예: 2000-11-11)으로 입력해주세요.")
            String birth,

            @NotNull(message = "성별을 선택해주세요.")
            GenderClass gender
    ) {
    }
}
