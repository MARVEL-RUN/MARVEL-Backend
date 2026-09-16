package kr.co.teambrain.marvelrun.admin.community.query.controller;

import kr.co.teambrain.marvelrun.admin.community.query.domain.QuestionSearchTarget;
import kr.co.teambrain.marvelrun.admin.community.query.dto.response.QuestionAndAnswerDetailResponse;
import kr.co.teambrain.marvelrun.admin.community.query.dto.response.QuestionAndAnswerResponse;
import kr.co.teambrain.marvelrun.admin.community.query.service.QuestionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/admin/questions")
public class QuestionQueryController {

    private final QuestionQueryService
            questionQueryService;


    @GetMapping
    public ResponseEntity<Page<QuestionAndAnswerResponse>>
    getQuestions(

            @RequestParam(required = false)
            String eventId,

            @RequestParam(
                    required = false,
                    defaultValue = "ALL"
            )
            QuestionSearchTarget target,

            @RequestParam(required = false)
            String keyword,

            @RequestParam(required = false)
            Boolean isAnswered,

            Pageable pageable
    ) {

        return ResponseEntity.ok(
                questionQueryService.readQuestions(
                        eventId,
                        target,
                        keyword,
                        isAnswered,
                        pageable
                )
        );
    }


    @GetMapping("/homepage")
    public ResponseEntity<Page<QuestionAndAnswerResponse>>
    getHomepageQuestions(

            @RequestParam(
                    required = false,
                    defaultValue = "ALL"
            )
            QuestionSearchTarget target,

            @RequestParam(required = false)
            String keyword,

            @RequestParam(required = false)
            Boolean isAnswered,

            Pageable pageable
    ) {

        return ResponseEntity.ok(
                questionQueryService.readHomepageQuestions(
                        target,
                        keyword,
                        isAnswered,
                        pageable
                )
        );
    }


    @GetMapping("/{questionId}")
    public ResponseEntity<QuestionAndAnswerDetailResponse>
    getQuestionDetail(
            @PathVariable
            String questionId
    ) {

        return ResponseEntity.ok(
                questionQueryService.readQuestionDetail(
                        questionId
                )
        );
    }
}