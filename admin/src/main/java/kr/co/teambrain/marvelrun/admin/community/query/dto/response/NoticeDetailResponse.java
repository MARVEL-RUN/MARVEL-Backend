package kr.co.teambrain.marvelrun.admin.community.query.dto.response;

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
}