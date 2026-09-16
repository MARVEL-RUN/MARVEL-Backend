package kr.co.teambrain.marvelrun.user.community.query.dto.response;

import kr.co.teambrain.marvelrun.user.community.query.dto.AnswerHeader;
import kr.co.teambrain.marvelrun.user.community.query.dto.QuestionHeader;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionAnswerResponse {

    private QuestionHeader questionHeader;

    /*
     * 미답변 Question이면 null.
     */
    private AnswerHeader answerHeader;
}