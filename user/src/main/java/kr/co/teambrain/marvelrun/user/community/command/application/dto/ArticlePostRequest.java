package kr.co.teambrain.marvelrun.user.community.command.application.dto;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ArticlePostRequest {
    @NotNull
    private String title;

    // content 내에 이미지 관련 src가 추가됩니다.
    @NotNull
    private String content;

    @NotNull
    private Boolean secret; // 비밀글 여부 (true == secret)
}
