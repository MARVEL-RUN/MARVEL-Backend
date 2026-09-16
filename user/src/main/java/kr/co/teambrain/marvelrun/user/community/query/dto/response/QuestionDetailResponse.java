package kr.co.teambrain.marvelrun.user.community.query.dto.response;

import kr.co.teambrain.marvelrun.user.community.command.application.domain.Question;
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

    private String content;

    private String author;

    private LocalDateTime createdAt;

    private boolean isSecret;

    public static QuestionDetailResponse from(
            Question question
    ) {

        return new QuestionDetailResponse(
                question.getId(),
                question.getTitle(),
                question.getContent(),
                question.getAuthorName(),
                question.getCreatedAt(),
                question.getIsSecret()
        );
    }
}