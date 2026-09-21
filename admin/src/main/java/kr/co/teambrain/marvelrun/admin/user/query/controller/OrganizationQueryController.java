package kr.co.teambrain.marvelrun.admin.user.query.controller;

import kr.co.teambrain.marvelrun.admin.user.query.dto.OrganizationDetailResponse;
import kr.co.teambrain.marvelrun.admin.user.query.dto.OrganizationListResponse;
import kr.co.teambrain.marvelrun.admin.user.query.dto.OrganizationSearchCondition;
import kr.co.teambrain.marvelrun.admin.user.query.service.OrganizationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/admin/organizations")
@RequiredArgsConstructor
public class OrganizationQueryController {

    private final OrganizationQueryService organizationQueryService;

    @GetMapping
    public ResponseEntity<Page<OrganizationListResponse>> getOrganizations(

            @RequestParam(required = true) // 특정 대회 안에서 조회한다고 가정
            String eventId,

            @RequestParam(required = false)
            String keyword,

            @RequestParam(defaultValue = "0")
            int page,

            @RequestParam(defaultValue = "10")
            int size
    ) {

        // 최신 생성일(createdAt) 및 ID 기준 내림차순 정렬 생성
        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")
                )
        );

        OrganizationSearchCondition condition = new OrganizationSearchCondition(
                eventId,
                keyword
        );

        return ResponseEntity.ok(
                organizationQueryService.getOrganizationList(
                        condition,
                        pageable
                )
        );
    }

    @GetMapping("/{organizationId}")
    public ResponseEntity<OrganizationDetailResponse> getOrganizationDetail(
            @PathVariable String organizationId
    ) {
        return ResponseEntity.ok(
                organizationQueryService.getOrganizationDetail(organizationId)
        );
    }
}