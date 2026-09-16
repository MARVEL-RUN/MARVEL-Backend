package kr.co.teambrain.marvelrun.user.community.query.controller;


import kr.co.teambrain.marvelrun.user.common.dto.PasswordInputRequest;
import kr.co.teambrain.marvelrun.user.community.query.dto.response.AnswerDetailResponse;
import kr.co.teambrain.marvelrun.user.community.query.service.AnswerQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/public/answers")
public class AnswerQueryController {

    private final AnswerQueryService
            answerQueryService;


    /**
     * 답변 상세.
     * <p>
     * Answer 자체 password는 없고,
     * 연결된 Question의 secret/password 정책을 사용한다.
     */
    @PostMapping("/{answerId}/detail")
    public ResponseEntity<AnswerDetailResponse>
    getAnswerDetail(

            @PathVariable
            String answerId,

            @RequestBody(required = false)
            PasswordInputRequest request
    ) {

        return ResponseEntity.ok(
                answerQueryService
                        .getAnswerDetail(
                                request,
                                answerId
                        )
        );
    }
}