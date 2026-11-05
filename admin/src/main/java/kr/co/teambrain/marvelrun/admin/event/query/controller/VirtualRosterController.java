package kr.co.teambrain.marvelrun.admin.event.query.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import kr.co.teambrain.marvelrun.admin.event.query.service.VirtualRosterExcelService;
import kr.co.teambrain.marvelrun.admin.event.query.support.TemporaryExcelResource;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/** 버추얼런 차수별 인쇄 업체용·패킹 업체용 명단 엑셀을 내려받는다. */
@Tag(name = "버추얼런 명단", description = "차수 마감 시각으로 인쇄 업체용·패킹 업체용 명단을 내려받습니다.")
@RestController
@RequestMapping("/v1/admin/virtual/{eventId}")
@RequiredArgsConstructor
public class VirtualRosterController {

    private final VirtualRosterExcelService virtualRosterExcelService;

    @Operation(summary = "인쇄 업체용 명단 엑셀 다운로드",
            description = "deadline은 차수의 마감 시각(KST)입니다. 참가 항목 하나가 한 행이며 배번, 이름, 부문, 차수 열을 가집니다. "
                    + "배번 길이·값 순이고 배번이 없는 항목은 끝에 둡니다. 마감 전에도 받을 수 있지만 배번이 비어 있을 수 있습니다.")
    @GetMapping("/print-list/excel/download")
    public ResponseEntity<Resource> downloadPrintList(@PathVariable String eventId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime deadline, HttpServletRequest request) {
        return respond(virtualRosterExcelService.createPrintList(eventId, deadline), request);
    }

    @Operation(summary = "패킹 업체용 명단 엑셀 다운로드",
            description = "deadline은 차수의 마감 시각(KST)입니다. 신청 하나(상자 하나)가 한 행이며 하위 종목별 배번·이름, 기념품, 받는 사람과 배송지를 담습니다. "
                    + "신청일시 순입니다.")
    @GetMapping("/packing-list/excel/download")
    public ResponseEntity<Resource> downloadPackingList(@PathVariable String eventId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime deadline, HttpServletRequest request) {
        return respond(virtualRosterExcelService.createPackingList(eventId, deadline), request);
    }

    /** 파일 생성이 끝난 뒤 동기 응답 변환기로 전송한다. */
    private ResponseEntity<Resource> respond(TemporaryExcelResource file, HttpServletRequest request) {
        file.attachTo(request);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(file.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.getFilename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Content-Type-Options", "nosniff").body(file);
    }
}
