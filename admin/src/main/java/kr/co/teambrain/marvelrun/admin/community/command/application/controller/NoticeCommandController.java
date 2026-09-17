package kr.co.teambrain.marvelrun.admin.community.command.application.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.teambrain.marvelrun.admin.common.dto.response.IdResponse;
import kr.co.teambrain.marvelrun.admin.common.exception.SuccessCode;
import kr.co.teambrain.marvelrun.admin.community.command.application.dto.NoticeCreate;
import kr.co.teambrain.marvelrun.admin.community.command.application.dto.NoticeUpdate;
import kr.co.teambrain.marvelrun.admin.community.command.application.service.NoticeCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1")
@PreAuthorize("hasAnyRole('총관리자', '게시판 관리자')")
@Tag(name = "공지사항 컨트롤러", description = "공지사항 생성/수정/삭제 컨트롤러. 공지사항 '생성'시에만 홈페이지/대회를 구분합니다.")
public class NoticeCommandController {

    private final NoticeCommandService noticeCommandService;

    @PostMapping(
            value = {"/event/{eventId}/notice", "/notice"}
    )
    @Operation(summary = "공지사항 생성", description = "공지사항 생성 기능입니다.")
    ResponseEntity<IdResponse> createEventNotice(
            @RequestBody NoticeCreate noticeCreate,
            @PathVariable(name = "eventId", required = false) String eventId

    ) {

        return ResponseEntity.ok(
                IdResponse.builder()
                        .id(noticeCommandService.createNotice(noticeCreate, eventId))
                        .build()
        );
    }


    @PutMapping(
            value = "/notice/{noticeId}"
    )
    @Operation(summary = "공지사항 수정", description = "공지사항 ID로 공지사항을 수정합니다.")
    ResponseEntity<SuccessCode> updateNotice(
            @RequestBody NoticeUpdate noticeUpdate,
            @PathVariable(name = "noticeId") String noticeId
    ) {

        noticeCommandService.updateNotice(noticeUpdate, noticeId);

        return ResponseEntity.ok(SuccessCode.UPDATE_SUCCESS);
    }

    @DeleteMapping(value = "/notice/{noticeId}")
    @Operation(summary = "공지사항 삭제", description = "공지사항 ID로 공지사항을 삭제합니다.")
    ResponseEntity<SuccessCode> deleteNotice(@PathVariable(name = "noticeId") String noticeId) {

        noticeCommandService.deleteNotice(noticeId);

        return ResponseEntity.ok(SuccessCode.DELETE_SUCCESS);
    }
}
