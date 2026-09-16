package kr.co.teambrain.marvelrun.user.community.query.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class QuestionHeader {

    private long no;

    private String id;

    private String title;

    private String authorName;

    private LocalDateTime createdAt;

    private boolean isSecret;

    private boolean isAnswered;
}