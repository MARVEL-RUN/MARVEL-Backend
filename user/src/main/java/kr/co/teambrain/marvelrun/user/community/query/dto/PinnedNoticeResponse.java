package kr.co.teambrain.marvelrun.user.community.query.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "공지가 속한 대회 id. 대회에 속하지 않은 공지는 null")
    private String eventId;
}
