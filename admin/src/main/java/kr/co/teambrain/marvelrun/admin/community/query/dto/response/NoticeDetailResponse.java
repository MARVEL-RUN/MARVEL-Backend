package kr.co.teambrain.marvelrun.admin.community.query.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
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

    private LocalDateTime createdAt;

@Schema(description = "공지가 속한 대회 id. 대회에 속하지 않은 공지는 null")
    private String eventId;
}