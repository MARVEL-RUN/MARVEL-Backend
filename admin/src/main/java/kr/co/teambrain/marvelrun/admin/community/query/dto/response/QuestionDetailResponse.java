package kr.co.teambrain.marvelrun.admin.community.query.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionDetailResponse {

    private String id;

    private String title;

    private String author;

    private String content;

    private LocalDateTime createdAt;

    private boolean secret;

    private boolean answered;
}