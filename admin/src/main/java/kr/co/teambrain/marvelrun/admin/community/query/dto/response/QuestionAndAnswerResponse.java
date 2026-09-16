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
public class QuestionAndAnswerResponse {

    private long no;

    private String questionId;

    private String questionTitle;

    private String authorName;

    private LocalDateTime questionCreatedAt;

    private boolean secret;

    private boolean answered;

    private String eventId;


    /*
     * 미답변 문의라면 아래 필드는 모두 null.
     */
    private String answerId;

    private String answerTitle;

    private String answerAuthorName;

    private LocalDateTime answerCreatedAt;
}