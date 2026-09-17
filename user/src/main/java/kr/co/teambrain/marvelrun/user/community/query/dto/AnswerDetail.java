package kr.co.teambrain.marvelrun.user.community.query.dto;

import java.time.LocalDateTime;

public record AnswerDetail(

        String answerId,

        String title,

        String content,

        String author,

        LocalDateTime createdAt,

        boolean isSecret,

        String questionPassword
) {
}