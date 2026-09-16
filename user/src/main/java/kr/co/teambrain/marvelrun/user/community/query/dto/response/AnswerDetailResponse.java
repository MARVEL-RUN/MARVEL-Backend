package kr.co.teambrain.marvelrun.user.community.query.dto.response;

import kr.co.teambrain.marvelrun.user.community.command.application.domain.Answer;

import java.time.LocalDateTime;

public record AnswerDetailResponse(

        String id,

        String title,

        String content,

        String author,

        LocalDateTime createdAt,

        boolean isSecret


) {
    public static AnswerDetailResponse from(
            Answer answer
    ) {

        return new AnswerDetailResponse(
                answer.getId(),
                answer.getTitle(),
                answer.getContent(),
                answer.getAdmin().getName(),
                answer.getCreatedAt(),
                answer.getQuestion().getIsSecret()
        );
    }
}