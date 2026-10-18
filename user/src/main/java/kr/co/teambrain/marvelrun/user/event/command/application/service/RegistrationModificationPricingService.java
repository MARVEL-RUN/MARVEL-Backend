package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationModificationCandidateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationModificationCandidateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.OrgRegistrationParticipantPricing;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationPrice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import kr.co.teambrain.marvelrun.user.discount.domain.DiscountLink;
import kr.co.teambrain.marvelrun.user.discount.service.DiscountPolicyResolver;
import kr.co.teambrain.marvelrun.user.discount.service.LinkDiscountEligibilityService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 정책검증을 통과한 수정 후보의 계약금액을 재계산한다.
 *
 * 실제 가격정책은 기존 RegistrationPricingService 한 곳에서만 판단한다.
 * 현재 Entity와 과거 Payment·Allocation은 변경하지 않는다.
 *
 * 호출자는 실제 수정 트랜잭션에서 Entity 변경 전에 이 서비스를 호출한다.
 * 이 클래스는 별도 트랜잭션을 시작하거나 외부 API를 호출하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RegistrationModificationPricingService {

    private final RegistrationPricingService registrationPricingService;
    private final DiscountPolicyResolver discountPolicies;
    private final LinkDiscountEligibilityService discountEligibility;
    private final RegistrationModificationClassifier classifier = new RegistrationModificationClassifier();

    /**
     * 개인 수정 후보의 이전 계약금액과 새 계약금액을 비교한다.
     *
     * 가격 계산에는 인증용 access.birth가 아닌 검증된 수정 후보 birth를 사용한다.
     */
    public RegistrationModificationPrice repricePersonal(
            RegistrationModificationCandidateContext candidate
    ) {
        return reprice(
                candidate.event(),
                candidate.currentRegistration(),
                candidate.eventCategory(),
                candidate.request().birth(), false, null, discountPolicies.resolveEventPolicies(candidate.event().getId())
        );
    }

    /**
     * 단체 최종 후보 목록을 순서대로 가격 계산한다.
     *
     * 기존 참가자는 계약금액 변경분을 계산한다.
     * 신규 참가자는 최초 계약금액만 계산한다.
     * 최종목록에서 빠진 제거 후보의 금액 처리는 수행하지 않는다.
     */
    public List<OrgRegistrationParticipantPricing> calculateOrganizationRegistrationModificationPrices(
            OrgRegistrationModificationCandidateContext candidate
    ) {
        List<OrgRegistrationParticipantPricing> results =
                new ArrayList<>(candidate.registrations().size());

        // 정책은 단체 전체에 재사용하고 취득 기간은 신규 추가 인원의 링크 승계에만 적용한다.
        List<DiscountModels.Policy> snapshot = discountPolicies.resolveEventPolicies(candidate.event().getId());
        DiscountLink inheritedLink = discountEligibility.inheritOrganizationLink(
                candidate.organization().getDiscountLinkId(), candidate.event().getId(), candidate.now(), snapshot);
        for (OrgRegistrationModificationCandidateContext.ParticipantCandidate participant
                : candidate.registrations()) {

            // 자원·가격 입력이 그대로인 기존 참가자는 과거 계약금액을 보존한다.
            Registration current = participant.currentRegistration();
            if (current != null && classifier.classifyOrganizationParticipant(current, participant.request())
                    != RegistrationModificationClassifier.Change.FULL) {
                results.add(new OrgRegistrationParticipantPricing(participant,
                        RegistrationModificationPrice.forExisting(current.getContractAmount(), current.getContractAmount())));
                continue;
            }

            RegistrationModificationPrice price =
                    reprice(
                            candidate.event(),
                            participant.currentRegistration(),
                            participant.eventCategory(),
                            participant.request().birth(), true, inheritedLink, snapshot
                    );

            results.add(
                    new OrgRegistrationParticipantPricing(
                            participant,
                            price
                    )
            );
        }

        return List.copyOf(results);
    }

    /**
     * 검증된 종목과 생년월일로 기존 가격 계산점을 호출한다.
     *
     * 이전 계약금액은 현재 Category 가격으로 재계산하지 않고
     * Registration에 저장된 계약금액을 그대로 보존한다.
     *
     * currentRegistration이 null이면 신규 단체 참가자다.
     */
    private RegistrationModificationPrice reprice(
            Event event,
            Registration currentRegistration,
            EventCategory candidateCategory,
            String candidateBirth,
            boolean organization,
            DiscountLink inheritedLink,
            List<DiscountModels.Policy> snapshot
    ) {
        BigDecimal oldContractAmount =
                currentRegistration == null
                        ? null
                        : Objects.requireNonNull(
                        currentRegistration.getContractAmount(),
                        "기존 Registration의 contractAmount가 누락되었습니다."
                );

        // 기존원은 저장된 자격, 신규원은 취득 기간을 통과한 단체 승계 자격을 사용한다.
        DiscountLink link = currentRegistration == null ? inheritedLink
                : currentRegistration.getDiscountLinkId() == null ? null
                : discountPolicies.resolveStoredLink(currentRegistration.getDiscountLinkId());
        DiscountModels.PriceResult result = registrationPricingService.calculateRegistrationPrice(
                event, candidateCategory, candidateBirth, organization, link, snapshot);
        return new RegistrationModificationPrice(oldContractAmount, result.finalAmount(),
                oldContractAmount == null ? null : result.finalAmount().subtract(oldContractAmount), result,
                currentRegistration == null && link != null ? link.getId() : null,
                currentRegistration == null && link != null ? link.getReferenceFilterId() : null);
    }
}
