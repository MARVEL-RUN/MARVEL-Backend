package kr.co.teambrain.marvelrun.admin.community.query.dto.response;

public record QuestionAndAnswerDetailResponse(

        QuestionDetailResponse questionDetail,

        AnswerDetailResponse answerDetail
) {
}