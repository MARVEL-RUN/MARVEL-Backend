package kr.co.teambrain.marvelrun.user.community.query.dto;

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

    // JPQL 생성자용 (no는 서비스에서 나중에 세팅)
    public NoticeListResponse(String id, String title, String category, LocalDateTime createdAt, String author, Long viewCount) {
        this.id = id;
        this.title = title;
        this.category = category;
        this.createdAt = createdAt;
        this.author = author;
        this.viewCount = viewCount;
        this.no = 0L; // 기본값
    }
}