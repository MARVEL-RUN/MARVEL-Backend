package kr.co.teambrain.marvelrun.user.community.command.application.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ArticlePatchRequest {
    @NotNull
    private String title;

    // content 내에 이미지 관련 src가 추가됩니다.
    @NotNull
    private String content;

    private boolean secret;

    private List<String> deletedAttachmentIds;
}
