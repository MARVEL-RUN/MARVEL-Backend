package kr.co.teambrain.marvelrun.admin.community.command.application.controller;

import kr.co.teambrain.marvelrun.admin.common.exception.SuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.teambrain.marvelrun.admin.common.dto.response.IdResponse;
import kr.co.teambrain.marvelrun.admin.community.command.application.dto.NoticeCreate;
import kr.co.teambrain.marvelrun.admin.community.command.application.dto.NoticeUpdate;
import kr.co.teambrain.marvelrun.admin.community.command.application.service.NoticeCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1")
@PreAuthorize("hasAnyRole('총관리자', '게시판 관리자')")
@Tag(name = "공지사항 컨트롤러", description = "공지사항 생성/수정/삭제 컨트롤러. 공지사항 '생성'시에만 홈페이지/대회를 구분합니다.")
public class NoticeCommandController {

    private final NoticeCommandService noticeCommandService;

    @PostMapping(
            value = { "/event/{eventId}/notice", "/notice"},
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(summary = "공지사항 생성", description = "공지사항 생성 기능입니다.")
    ResponseEntity<IdResponse> createEventNotice(
            @RequestPart(name = "noticeCreate") NoticeCreate noticeCreate,
            @PathVariable(name = "eventId", required = false) String eventId

    ) {

        return ResponseEntity.ok(
                IdResponse.builder()
                        .id(noticeCommandService.createNotice(noticeCreate, eventId))
                        .build()
        );
    }


    @PutMapping(
            value = "/notice/{noticeId}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(summary = "공지사항 수정", description = "공지사항 ID로 공지사항을 수정합니다.")
    ResponseEntity<SuccessCode> updateNotice(
            @Parameter(description = "noticeUpdate의 deleteFileUrls에는 삭제될 첨부파일의 url을 담아주시면 됩니다. 삭제되는게 없는경우 빈 배열로 보내주시면 됩니다.")
            @RequestPart(name = "noticeUpdate") NoticeUpdate noticeUpdate,
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
