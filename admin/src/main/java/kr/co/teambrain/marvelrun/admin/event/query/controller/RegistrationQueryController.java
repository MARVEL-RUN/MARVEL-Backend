package kr.co.teambrain.marvelrun.admin.event.query.controller;

import kr.co.teambrain.marvelrun.admin.event.query.dto.response.EventStatisticsResponse;
import kr.co.teambrain.marvelrun.admin.event.query.dto.response.RegistrationDetailResponse;
import kr.co.teambrain.marvelrun.admin.event.query.dto.response.RegistrationListResponse;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationSearchCondition;
import kr.co.teambrain.marvelrun.admin.event.query.service.RegistrationQueryService;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 기존 관리자 조회에 그룹 표시와 코드·미선택 검색을 제공한다. */
@RestController
@RequestMapping("/v1/admin/registrations")
@RequiredArgsConstructor
public class RegistrationQueryController {

    private final RegistrationQueryService registrationQueryService;

    /** 그룹 코드 및 미선택 조건을 기존 신청 목록 검색에 결합한다. */
    @Operation(summary = "신청 목록 조회", description = "현재 그룹을 표시하며 startGroupCode와 startGroupUnselected로 검색합니다.")
    @ApiResponse(responseCode = "200", description = "신청 목록과 페이지 정보")
    @GetMapping
    public ResponseEntity<Page<RegistrationListResponse>>
    getRegistrations(

            @RequestParam(required = true) // 대회 식별자는 필수값으로 권장 (특정 대회를 누르고 들어오므로)
            String eventId,

            @RequestParam(required = false)
            String type,

            @RequestParam(required = false)
            String eventCategoryId,

            @RequestParam(required = false)
            RegistrationStatus status,

            @RequestParam(required = false)
            String keyword,

            @RequestParam(required = false) String startGroupCode,
            @RequestParam(required = false) Boolean startGroupUnselected,

            @RequestParam(
                    defaultValue = "0"
            )
            int page,

            @RequestParam(
                    defaultValue = "10"
            )
            int size
    ) {

        // 최신 신청일 및 ID 기준 내림차순 정렬 생성
        Pageable pageable =
                PageRequest.of(
                        page,
                        size,
                        Sort.by(
                                Sort.Order.desc("registrationDate"),
                                Sort.Order.desc("id")
                        )
                );

        // 분리된 검색 파라미터들을 비즈니스 로직 전달을 위해 DTO로 응집
        RegistrationSearchCondition condition = new RegistrationSearchCondition(
                eventId,
                type,
                eventCategoryId,
                status,
                keyword,
                startGroupCode,
                startGroupUnselected
        );

        return ResponseEntity.ok(
                registrationQueryService.getRegistrationList(
                        condition,
                        pageable
                )
        );
    }

    /** 현재 그룹 선택을 포함한 신청 상세를 제공한다. */
    @Operation(summary = "신청 상세 조회", description = "그룹 ID·code·미선택 여부와 기존 상세 정보를 제공합니다.")
    @ApiResponse(responseCode = "200", description = "신청 상세 정보")
    @GetMapping("/{registrationId}")
    public ResponseEntity<RegistrationDetailResponse> getRegistrationDetail(
            @PathVariable String registrationId
    ) {
        return ResponseEntity.ok(
                registrationQueryService.getRegistrationDetail(registrationId)
        );
    }

    @GetMapping("/{eventId}/statistics")
    public ResponseEntity<EventStatisticsResponse> getEventStatistics(
            @PathVariable String eventId
    ) {
        return ResponseEntity.ok(
                registrationQueryService.getEventStatistics(eventId)
        );
    }

}