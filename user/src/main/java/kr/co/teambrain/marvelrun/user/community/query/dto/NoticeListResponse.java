package kr.co.teambrain.marvelrun.user.community.query.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class NoticeListResponse {

    private Long no;
    private String id;
    private String title;
    private String category;
    private LocalDateTime createdAt;
    private String author;
    private Long viewCount;
@Schema(description = "공지가 속한 대회 id. 대회에 속하지 않은 공지는 null")
    private String eventId;

    // JPQL 생성자용 (no는 서비스에서 나중에 세팅)
    public NoticeListResponse(String id, String title, String category, LocalDateTime createdAt, String author, Long viewCount, String eventId) {
        this.id = id;
        this.title = title;
        this.category = category;
        this.createdAt = createdAt;
        this.author = author;
        this.viewCount = viewCount;
        this.eventId = eventId;
        this.no = 0L; // 기본값
    }
}