package kr.co.teambrain.marvelrun.user.community.query.dto;

import java.time.LocalDateTime;

public record AnswerHeaderProjection(

        String questionId,

        String id,

        String title,

        String authorName,

        LocalDateTime createdAt
) {
}