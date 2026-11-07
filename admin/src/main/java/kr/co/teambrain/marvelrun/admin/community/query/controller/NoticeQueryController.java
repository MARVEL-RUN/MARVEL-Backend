package kr.co.teambrain.marvelrun.admin.community.query.controller;

import io.swagger.v3.oas.annotations.Operation;
import kr.co.teambrain.marvelrun.admin.community.query.domain.NoticeSearchTarget;
import kr.co.teambrain.marvelrun.admin.community.query.domain.NoticeSortType;
import kr.co.teambrain.marvelrun.admin.community.query.dto.response.NoticeCategoryResponse;
import kr.co.teambrain.marvelrun.admin.community.query.dto.response.NoticeDetailResponse;
import kr.co.teambrain.marvelrun.admin.community.query.dto.response.NoticeListResponse;
import kr.co.teambrain.marvelrun.admin.community.query.service.NoticeQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/public/notices")
public class NoticeQueryController {

    private final NoticeQueryService noticeQueryService;


    @Operation(summary = "공지사항 목록 조회", description = "eventId를 보내면 그 대회의 공지, 보내지 않으면 대회에 속하지 않은 공지만 조회한다. 응답의 각 공지에 eventId가 있으며 대회에 속하지 않은 공지는 null이다.")
    @GetMapping
    public ResponseEntity<Page<NoticeListResponse>>
    getQuestionPage(

            @RequestParam(
                    required = false
            )
            String eventId,

            @RequestParam(
                    required = false,
                    defaultValue = "ALL"
            )
            NoticeSearchTarget target,

            @RequestParam(
                    required = false
            )
            String keyword,

            @RequestParam(
                    defaultValue = "0"
            )
            int page,

            @RequestParam(
                    defaultValue = "20"
            )
            int size,

            @RequestParam(
                    defaultValue = "5"
            )
            int limit,

            @RequestParam(
                    defaultValue = "LATEST"
            )
            NoticeSortType sort
    ) {

        Pageable pageable =
                PageRequest.of(
                        page,
                        size,
                        createSort(sort)
                );

        return ResponseEntity.ok(
                noticeQueryService
                        .getNoticePage(
                                eventId,
                                target,
                                keyword,
                                pageable
                        )
        );
    }


    /**
     * 공지사항 상세
     */
    @Operation(summary = "공지사항 상세 조회", description = "응답에 공지가 속한 대회 eventId가 있다. 대회에 속하지 않은 공지는 null이다.")
    @PostMapping("/{noticeId}/detail")
    public ResponseEntity<NoticeDetailResponse>
    getQuestionDetail(
            @PathVariable
            String noticeId
    ) {

        return ResponseEntity.ok(
                noticeQueryService
                        .getNoticeDetail(
                                noticeId
                        )
        );
    }

    @GetMapping("/notice/category")
    @Operation(summary = "공지사항 카테고리 조회", description = "공지사항의 카테고리 목록을 조회합니다.")
    ResponseEntity<List<NoticeCategoryResponse>> readNoticeCategory() {

        return ResponseEntity.ok(noticeQueryService.readAllNoticeCategory());
    }

    private Sort createSort(
            NoticeSortType sortType
    ) {

        return switch (sortType) {

            case LATEST ->
                    Sort.by(
                            Sort.Order.desc("createdAt"),
                            Sort.Order.desc("id")
                    );

            case OLDEST ->
                    Sort.by(
                            Sort.Order.asc("createdAt"),
                            Sort.Order.asc("id")
                    );
        };
    }

}
