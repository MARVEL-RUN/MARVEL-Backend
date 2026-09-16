package kr.co.teambrain.marvelrun.admin.community.query.dto;

import java.time.LocalDateTime;

public interface AdminAnswerDetailProjection {

    String getId();

    String getTitle();

    String getContent();

    String getAuthor();

    LocalDateTime getCreatedAt();
}