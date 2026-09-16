package kr.co.teambrain.marvelrun.user.community.query.controller;

/**
 * 문의 목록 / 검색
 */

import kr.co.teambrain.marvelrun.user.common.dto.PasswordInputRequest;
import kr.co.teambrain.marvelrun.user.community.query.domain.QuestionSearchTarget;
import kr.co.teambrain.marvelrun.user.community.query.domain.QuestionSortType;
import kr.co.teambrain.marvelrun.user.community.query.dto.response.QuestionAndAnswerDetailResponse;
import kr.co.teambrain.marvelrun.user.community.query.dto.response.QuestionAnswerResponse;
import kr.co.teambrain.marvelrun.user.community.query.dto.response.QuestionDetailResponse;
import kr.co.teambrain.marvelrun.user.community.query.service.QuestionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/public/questions")
public class QuestionQueryController {

    private final QuestionQueryService
            questionQueryService;


    @GetMapping
    public ResponseEntity<Page<QuestionAnswerResponse>>
    getQuestionPage(

            @RequestParam(
                    required = false
            )
            String eventId,

            @RequestParam(
                    required = false,
                    defaultValue = "ALL"
            )
            QuestionSearchTarget target,

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
                    defaultValue = "LATEST"
            )
            QuestionSortType sort
    ) {

        Pageable pageable =
                PageRequest.of(
                        page,
                        size,
                        createSort(sort)
                );

        return ResponseEntity.ok(
                questionQueryService
                        .getQuestionPage(
                                eventId,
                                target,
                                keyword,
                                pageable
                        )
        );
    }


    /**
     * 문의 상세.
     * <p>
     * 공개글:
     * password null 가능.
     * <p>
     * 비밀글:
     * Question password 필요.
     */
    @PostMapping("/{questionId}/detail")
    public ResponseEntity<QuestionAndAnswerDetailResponse>
    getQuestionDetail(

            @PathVariable
            String questionId,

            @RequestBody(required = false)
            PasswordInputRequest request
    ) {

        return ResponseEntity.ok(
                questionQueryService
                        .getQuestionDetail(
                                request,
                                questionId
                        )
        );
    }

    private Sort createSort(
            QuestionSortType sortType
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
