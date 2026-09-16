package kr.co.teambrain.marvelrun.user.community.query.dto;

import java.time.LocalDateTime;

public interface QuestionProjection {

    String getId();

    String getTitle();

    String getAuthorName();

    LocalDateTime getCreatedAt();

    Boolean getIsSecret();

    Boolean getIsAnswered();
}