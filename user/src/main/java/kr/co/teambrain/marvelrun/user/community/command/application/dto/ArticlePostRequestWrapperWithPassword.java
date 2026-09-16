package kr.co.teambrain.marvelrun.user.community.command.application.dto;


import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ArticlePostRequestWrapperWithPassword {
    @NotNull
    @Valid
    private ArticlePostRequest post;

    @NotNull
    private String nickName;

    @Schema(
            description = "비밀번호 (선택 입력).<br>" +
                    "8~20자, 영문 대/소문자·숫자·특수문자만 허용하며 공백은 불가합니다.<br>" +
                    "미입력 시 기존 비밀번호 유지됩니다.",
            example = "Abcdef12!",
            pattern = "^(?:$|(?=\\S{8,20}$)[\\p{Alnum}\\p{Punct}]+)$",
            maxLength = 20,
            minLength = 4 // 20251202 클라이언트 요청사항에 의거, 8 -> 4자리로 완화
    )
    @Pattern(
            regexp = "^(?:$|(?=\\S{4,20}$)[\\p{Alnum}\\p{Punct}]+)$",
            message = "비밀번호는 4~20자, 영문 대/소문자·숫자·특수문자만 허용하며 공백은 불가합니다."
    )
    @NotBlank // 비밀번호 필수 기입
    private String password;
}
