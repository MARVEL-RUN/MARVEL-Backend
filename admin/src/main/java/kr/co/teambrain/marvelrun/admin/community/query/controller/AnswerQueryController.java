package kr.co.teambrain.marvelrun.admin.community.query.controller;


import kr.co.teambrain.marvelrun.admin.community.query.dto.response.AnswerDetailResponse;
import kr.co.teambrain.marvelrun.admin.community.query.service.AnswerQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/admin/answers")
public class AnswerQueryController {

    private final AnswerQueryService
            answerQueryService;


    @GetMapping("/{answerId}")
    public ResponseEntity<AnswerDetailResponse>
    getAnswerDetail(
            @PathVariable
            String answerId
    ) {

        return ResponseEntity.ok(
                answerQueryService.readAnswerDetail(
                        answerId
                )
        );
    }
}