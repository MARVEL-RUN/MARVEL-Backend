package kr.co.teambrain.marvelrun.admin.common.dto.request;

import jakarta.validation.constraints.NotBlank;

public record LoginIdResetRequest (
    @NotBlank(message = "새 아이디를 입력해주세요.")
    String newLoginId
){ }
