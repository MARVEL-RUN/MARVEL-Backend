package kr.co.teambrain.marvelrun.user.discount.service;

import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.discount.domain.DiscountLink;
import kr.co.teambrain.marvelrun.user.discount.domain.DiscountPolicy;
import kr.co.teambrain.marvelrun.user.discount.repository.DiscountLinkRepository;
import kr.co.teambrain.marvelrun.user.discount.repository.DiscountPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 신규 링크 취득과 기존 단체의 기간 내 승계를 판단한다. */
@Service
@RequiredArgsConstructor
public class LinkDiscountEligibilityService {
    private final DiscountLinkRepository links;
    private final DiscountPolicyRepository policies;
    private final DiscountPolicyResolver resolver;

    /** 코드가 없는 요청은 일반 신청이며 실제 코드는 신규 자격 요건을 검증한다. */
    public DiscountLink acquireRegistrationLink(String code, String eventId, boolean organization,
        LocalDateTime now, List<DiscountModels.Policy> snapshot) {

        // 선택 입력인 코드가 비어 있으면 일반 신청 흐름을 유지한다.
        if (code == null || code.isBlank()) {
            return null;
        }

        // 공개 코드의 원본 링크와 정책 소속 대회를 확인한다.
        DiscountLink link = links.findByCode(code.strip()).filter(row -> row.getCode().equals(code.strip()))
            .orElseThrow(() -> new CustomException(ErrorCode.REGISTRATION_DISCOUNT_LINK_UNAVAILABLE));
        DiscountPolicy policyRow = policies.findById(link.getPolicyId())
            .orElseThrow(() -> new CustomException(ErrorCode.DISCOUNT_CONFIGURATION_ERROR));
        if (!eventId.equals(policyRow.getEventId())) {
            throw new CustomException(ErrorCode.REGISTRATION_DISCOUNT_LINK_UNAVAILABLE);
        }

        // 신규 자격은 종목 선택과 별개로 취득 기간 및 개인·단체 유형을 검증한다.
        DiscountModels.Policy policy = resolver.resolveLinkPolicy(link, eventId, snapshot);
        if (!withinAcquisitionPeriod(link, now)
            || (policy.applicationType() != DiscountModels.ApplicationType.BOTH
            && policy.applicationType() != (organization ? DiscountModels.ApplicationType.ORGANIZATION
            : DiscountModels.ApplicationType.PERSONAL))) {
            throw new CustomException(ErrorCode.REGISTRATION_DISCOUNT_LINK_UNAVAILABLE);
        }
        return link;
    }

    /** 기존 단체 자격은 보존하고 새 참가자에게만 신규 취득 기간을 적용한다. */
    public DiscountLink inheritOrganizationLink(String linkId, String eventId, LocalDateTime now,
        List<DiscountModels.Policy> snapshot) {
        if (linkId == null) {
            return null;
        }
        DiscountLink link = resolver.resolveStoredLink(linkId);
        DiscountModels.Policy policy = resolver.resolveLinkPolicy(link, eventId, snapshot);
        if (policy.applicationType() == DiscountModels.ApplicationType.PERSONAL) {
            throw new CustomException(ErrorCode.DISCOUNT_CONFIGURATION_ERROR);
        }

        // 기존 단체의 자격은 해제하지 않고 추가 참가자의 신규 승계 여부만 반환한다.
        return withinAcquisitionPeriod(link, now) ? link : null;
    }

    /** 시작 시각은 포함하고 종료 시각은 제외한다. */
    public boolean withinAcquisitionPeriod(DiscountLink link, LocalDateTime now) {
        return !now.isBefore(link.getAcquisitionStartAt()) && now.isBefore(link.getAcquisitionEndAt());
    }
}
