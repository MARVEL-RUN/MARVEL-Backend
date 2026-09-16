package kr.co.teambrain.marvelrun.admin.community.command.application.controller;

import kr.co.teambrain.marvelrun.admin.common.dto.response.IdResponse;
import kr.co.teambrain.marvelrun.admin.community.command.application.dto.AnswerRequest;
import kr.co.teambrain.marvelrun.admin.community.command.application.dto.AnswerUpdate;
import kr.co.teambrain.marvelrun.admin.community.command.application.service.AnswerCommandService;
import lombok.RequiredArgsConstructor;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1")
public class AnswerCommandController {

    private final AnswerCommandService
            answerCommandService;


    @PostMapping(
            value = "/questions/{questionId}/answer",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<IdResponse> createAnswer(
            @PathVariable
            String questionId,

            @RequestBody
            AnswerRequest answerRequest
    ) {

        return ResponseEntity.ok(
                IdResponse.builder()
                        .id(
                                answerCommandService.createAnswer(
                                        answerRequest,
                                        questionId
                                )
                        )
                        .build()
        );
    }


    @PatchMapping(
            value = "/answers/{answerId}",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<Void> updateAnswer(
            @PathVariable
            String answerId,

            @RequestBody
            AnswerUpdate answerUpdate
    ) {

        answerCommandService.updateAnswer(
                answerUpdate,
                answerId
        );


        return ResponseEntity.noContent()
                .build();
    }


    @DeleteMapping("/answers/{answerId}")
    public ResponseEntity<Void> deleteAnswer(
            @PathVariable
            String answerId
    ) {

        answerCommandService.deleteAnswer(
                answerId
        );


        return ResponseEntity.noContent()
                .build();
    }
}