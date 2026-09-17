package kr.co.teambrain.marvelrun.user.community.query.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PinnedNoticeResponse {
    private String id;
    private String title;
    private String category;
    private LocalDateTime createdAt;
    private String author;
    private Long viewCount;
}
