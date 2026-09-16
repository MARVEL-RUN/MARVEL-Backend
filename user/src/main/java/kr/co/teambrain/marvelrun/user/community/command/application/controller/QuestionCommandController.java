package kr.co.teambrain.marvelrun.user.community.command.application.controller;

import kr.co.teambrain.marvelrun.user.common.dto.PasswordInputRequest;
import kr.co.teambrain.marvelrun.user.community.command.application.dto.ArticlePatchRequestWrapperWithPassword;
import kr.co.teambrain.marvelrun.user.community.command.application.dto.ArticlePostRequestWrapperWithPassword;
import kr.co.teambrain.marvelrun.user.community.command.application.service.QuestionCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/public/questions")
public class QuestionCommandController {

    private final QuestionCommandService
            questionCommandService;


    /**
     * 문의 작성
     * <p>
     * eventId == null
     * → 일반 문의
     * <p>
     * eventId != null
     * → 특정 대회 문의
     */
    @PostMapping
    public ResponseEntity<String> writeQuestion(
            @RequestBody
            ArticlePostRequestWrapperWithPassword request,

            @RequestParam(
                    required = false
            )
            String eventId
    ) {

        String questionId =
                questionCommandService
                        .writeQuestionArticleInEvent(
                                request,
                                eventId
                        );


        return ResponseEntity.ok(
                questionId
        );
    }


    /**
     * 문의 수정.
     * <p>
     * 답변 완료 이후 수정 불가.
     */
    @PatchMapping("/{questionId}")
    public ResponseEntity<Void> patchQuestion(

            @PathVariable
            String questionId,

            @RequestBody
            ArticlePatchRequestWrapperWithPassword request
    ) {

        questionCommandService
                .patchQuestionArticleInEvent(
                        request,
                        questionId
                );


        return ResponseEntity.noContent()
                .build();
    }


    /**
     * 문의 삭제.
     * <p>
     * KMA 정책 계승:
     * 답변 완료 여부와 무관하게
     * password가 맞으면 삭제 가능.
     */
    @DeleteMapping("/{questionId}")
    public ResponseEntity<Void> deleteQuestion(

            @PathVariable
            String questionId,

            @RequestBody
            PasswordInputRequest request
    ) {

        questionCommandService
                .deleteQuestionArticle(
                        request,
                        questionId
                );


        return ResponseEntity.noContent()
                .build();
    }
}