package kr.co.teambrain.marvelrun.user.community.query.dto.response;

import java.time.LocalDateTime;

public record AnswerDetailResponse(

        String id,

        String title,

        String content,

        String author,

        LocalDateTime createdAt,

        boolean isSecret
) {
}