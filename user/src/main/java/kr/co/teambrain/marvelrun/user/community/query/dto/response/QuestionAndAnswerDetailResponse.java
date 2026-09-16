package kr.co.teambrain.marvelrun.user.community.query.dto.response;

public record QuestionAndAnswerDetailResponse(

        QuestionDetailResponse questionDetail,

        AnswerDetailResponse answerDetail
) {
}