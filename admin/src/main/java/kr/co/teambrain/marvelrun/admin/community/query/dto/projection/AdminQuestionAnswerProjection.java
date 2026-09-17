package kr.co.teambrain.marvelrun.admin.community.query.dto.projection;

import java.time.LocalDateTime;

public interface AdminQuestionAnswerProjection {

    String getQuestionId();

    String getQuestionTitle();

    String getAuthorName();

    LocalDateTime getQuestionCreatedAt();

    Boolean getSecret();

    Boolean getAnswered();

    String getEventId();

    String getAnswerId();

    String getAnswerTitle();

    String getAnswerAuthorName();

    LocalDateTime getAnswerCreatedAt();
}