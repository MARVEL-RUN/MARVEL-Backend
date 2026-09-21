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
                candidate.request().birth()
        );
    }

    /**
     * 단체 최종 후보 목록을 순서대로 가격 계산한다.
     *
     * 기존 참가자는 계약금액 변경분을 계산한다.
     * 신규 참가자는 최초 계약금액만 계산한다.
     * 최종목록에서 빠진 제거 후보의 금액 처리는 수행하지 않는다.
     */
    public List<OrgRegistrationParticipantPricing> repriceOrganization(
            OrgRegistrationModificationCandidateContext candidate
    ) {
        List<OrgRegistrationParticipantPricing> results =
                new ArrayList<>(candidate.registrations().size());

        for (OrgRegistrationModificationCandidateContext.ParticipantCandidate participant
                : candidate.registrations()) {

            RegistrationModificationPrice price =
                    reprice(
                            candidate.event(),
                            participant.currentRegistration(),
                            participant.eventCategory(),
                            participant.request().birth()
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
            String candidateBirth
    ) {
        BigDecimal oldContractAmount =
                currentRegistration == null
                        ? null
                        : Objects.requireNonNull(
                        currentRegistration.getContractAmount(),
                        "기존 Registration의 contractAmount가 누락되었습니다."
                );

        BigDecimal newContractAmount =
                Objects.requireNonNull(
                        registrationPricingService.calculateContractAmount(
                                event,
                                candidateCategory,
                                candidateBirth
                        ),
                        "Pricing 계산 결과가 누락되었습니다."
                );

        if (currentRegistration == null) {
            return RegistrationModificationPrice.forNew(
                    newContractAmount
            );
        }

        return RegistrationModificationPrice.forExisting(
                oldContractAmount,
                newContractAmount
        );
    }
}