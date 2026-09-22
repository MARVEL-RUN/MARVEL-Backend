package kr.co.teambrain.marvelrun.admin.event.query.controller;

import jakarta.servlet.http.HttpServletResponse;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationSearchCondition;
import kr.co.teambrain.marvelrun.admin.event.query.service.RegistrationExcelService;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

/** 관리자 신청 목록의 전체·검색 결과·선택 항목을 엑셀로 다운로드한다. */
@RestController
@RequestMapping("/v1/admin/registrations")
@RequiredArgsConstructor
public class RegistrationExcelController {

    private final RegistrationExcelService registrationExcelService;

    /** 대회와 검색 조건에 해당하는 신청 목록 전체를 다운로드한다. */
    @GetMapping("/excel/download")
    public void download(
            @RequestParam("eventId") String eventId,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "eventCategoryId", required = false) String eventCategoryId,
            @RequestParam(value = "status", required = false) RegistrationStatus status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "organizationId", required = false) String organizationId,
            HttpServletResponse response
    ) throws IOException {
        registrationExcelService.download(
                new RegistrationSearchCondition(eventId, type, eventCategoryId, status, keyword),
                organizationId, null, response
        );
    }

    /** 선택 ID가 있으면 해당 항목만, 없으면 해당 대회의 전체 목록을 다운로드한다. */
    @PostMapping("/excel/download")
    public void downloadSelected(
            @RequestParam("eventId") String eventId,
            @RequestBody(required = false) SelectionRequest request,
            HttpServletResponse response
    ) throws IOException {
        registrationExcelService.download(
                new RegistrationSearchCondition(eventId, null, null, null, null),
                null, request == null ? null : request.registrationIds(), response
        );
    }

    /** 선택 다운로드에 사용할 신청 식별자 목록이다. */
    public record SelectionRequest(List<String> registrationIds) {
    }
}
