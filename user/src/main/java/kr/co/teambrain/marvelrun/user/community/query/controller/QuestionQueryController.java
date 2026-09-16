package kr.co.teambrain.marvelrun.user.community.query.controller;

/**
 * 문의 목록 / 검색
 */

import kr.co.teambrain.marvelrun.user.common.dto.PasswordInputRequest;
import kr.co.teambrain.marvelrun.user.community.query.domain.QuestionSearchTarget;
import kr.co.teambrain.marvelrun.user.community.query.dto.response.QuestionAnswerResponse;
import kr.co.teambrain.marvelrun.user.community.query.dto.response.QuestionDetailResponse;
import kr.co.teambrain.marvelrun.user.community.query.service.QuestionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/questions")
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

            Pageable pageable
    ) {

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
    public ResponseEntity<QuestionDetailResponse>
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
}
