package kr.co.teambrain.marvelrun.user.community.query.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnswerHeader {

    private long no;

    private String id;

    private String title;

    private String authorName;

    private LocalDateTime createdAt;


    public AnswerHeader(
            String id,
            String title,
            String authorName,
            LocalDateTime createdAt
    ) {

        this.id = id;
        this.title = title;
        this.authorName = authorName;
        this.createdAt = createdAt;
    }
}