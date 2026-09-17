package kr.co.teambrain.marvelrun.user.community.query.dto.response;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NoticeDetailResponse {

    private String id;

    private String title;

    private String content;

    private String author;

    private String noticeCategoryId;

    private Long viewCount;

    private LocalDateTime createdAt;

    private List<String> attachmentUrls;
}
