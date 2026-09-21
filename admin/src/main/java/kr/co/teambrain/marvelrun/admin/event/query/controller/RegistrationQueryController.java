package kr.co.teambrain.marvelrun.admin.event.query.controller;

import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationDetailResponse;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationListResponse;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationSearchCondition;
import kr.co.teambrain.marvelrun.admin.event.query.service.RegistrationQueryService;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/admin/registrations")
@RequiredArgsConstructor
public class RegistrationQueryController {

    private final RegistrationQueryService registrationQueryService;

    @GetMapping
    public ResponseEntity<Page<RegistrationListResponse>>
    getRegistrations(

            @RequestParam(required = false)
            String type,

            @RequestParam(required = false)
            String eventCategoryId,

            @RequestParam(required = false)
            RegistrationStatus status,

            @RequestParam(required = false)
            String keyword,

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
                type,
                eventCategoryId,
                status,
                keyword
        );

        return ResponseEntity.ok(
                registrationQueryService.getRegistrationList(
                        condition,
                        pageable
                )
        );
    }

    @GetMapping("/{registrationId}")
    public ResponseEntity<RegistrationDetailResponse> getRegistrationDetail(
            @PathVariable String registrationId
    ) {
        return ResponseEntity.ok(
                registrationQueryService.getRegistrationDetail(registrationId)
        );
    }
}