package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.CapacityType;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityHoldRequest;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityRequirementDiff;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityRequirementInput;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.*;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationModificationCandidateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.OrgRegistrationModificationResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.OrgRegistrationParticipantPricing;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationPrice;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationModificationParticipantRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationParticipantRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationModificationAccessValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationModificationCandidateValidator;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;

/**
 * 단체 최종 구성원 목록을 기준으로 추가·수정·제거를 반영한다.
 *
 * 전체 후보 정책검증과 가격 계산을 마친 뒤 실제 변경을 시작한다.
 * 모든 신청·예약·카운터 변경은 외부 수정 Use Case의 같은 Tx에 속한다.
 *
 * 금융 상태·주문 정산은 상위 CommandService가 이어서 수행한다.
 * 대회 다음 단체를 NOWAIT로 확보하고 Payment·구성원 보호 후 금융 충돌 검사·READY 무효화를 수행한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class OrgRegistrationModificationService {

    private final RegistrationCapacityService registrationCapacityService;
    private final OrgRegistrationModificationAccessValidator accessValidator;
    private final OrgRegistrationModificationCandidateValidator candidateValidator;
    private final RegistrationModificationPricingService pricingService;

    private final RegistrationCommandRepository registrationRepository;
    private final ReservationCommandRepository reservationRepository;
    private final CapacityCommandRepository capacityRepository;

    private final CapacityHoldService holdService;
    private final CapacityRequirementResolver requirementResolver;
    private final ReservationCapacityDiffService diffService;
    private final CapacityModificationService modificationService;
    private final ReservationRemovalService removalService;
    private final OrgRegistrationModificationGuard modificationGuard;
    private final RegistrationModificationPaymentGuard paymentGuard;

    /**
     * 수정 요청의 최종 구성원 전체를 검증하여 단체에 반영한다.
     *
     * 요청에서 빠진 기존 구성원은 제거 대상으로 판정한다.
     * 신규와 기존 구성원의 확보 실패 시 제거 처리까지 포함하여 전체 롤백한다.
     */
    public OrgRegistrationModificationResult modify(
            String eventId,
            String organizationId,
            OrgRegistrationModificationRequest request,
            LocalDateTime now
    ) {
        return modify(eventId, organizationId, request, now, null);
    }

    /** Event 다음 단체 잠금을 대기 없이 확보하며, 경합이나 오래된 구성원 snapshot이면 전체 수정을 거절한다. */
    public OrgRegistrationModificationResult modify(
            String eventId,
            String organizationId,
            OrgRegistrationModificationRequest request,
            LocalDateTime now,
            OrgRegistrationModificationAccessContext observed
    ) {
        registrationCapacityService.lockEvent(eventId);

        OrgRegistrationModificationAccessContext access = observed == null
                ? accessValidator.validate(eventId, organizationId, request, now) : observed;

        // 개인정보 수정이 단체를 보호 중이면 다른 잠금 조회로 진입하기 전에 즉시 거절한다.
        modificationGuard.lockOrganizationWithoutWaiting(access);

        List<Payment> lockedPayments =
                paymentGuard.lockOrganization(eventId, organizationId);

        // 구성원 버전 보호는 Payment 잠금 뒤에 수행한다.
        modificationGuard.protect(access);
        paymentGuard.prepareLockedPayments(lockedPayments);

        OrgRegistrationModificationCandidateContext candidate = candidateValidator.validate(access);

        List<OrgRegistrationParticipantPricing> priced =
                pricingService.repriceOrganization(candidate);

        List<OrgRegistrationParticipantPricing> existing =
                priced.stream()
                        .filter(p -> p.candidate().currentRegistration() != null)
                        .toList();

        List<OrgRegistrationParticipantPricing> added =
                priced.stream()
                        .filter(p -> p.candidate().currentRegistration() == null)
                        .toList();

        Set<String> retainedIds =
                existing.stream()
                        .map(p -> p.candidate().currentRegistration().getId())
                        .collect(Collectors.toSet());

        List<Registration> removed =
                candidate.currentRegistrations().stream()
                        .filter(r -> !retainedIds.contains(r.getId()))
                        .sorted(Comparator.comparing(Registration::getId))
                        .toList();

        List<OrgRegistrationModificationResult.Member> results =
                new ArrayList<>();

        /*
         * 신규 참가자의 전체 자원을 먼저 확보한다.
         * 제거 예정 참가자의 반환 수량을 선사용하지 않는다.
         */
        createAndHoldNew(candidate, added, results, now);

        /*
         * 기존 참가자의 필요량을 일괄 계산하고 정원 점유를 이동한다.
         * 실제 Registration 변경은 Capacity 이동 성공 이후 수행한다.
         */
        moveExisting(eventId, candidate, existing, now);

        /*
         * 제거 대상의 실제 점유를 반환한다.
         * HELD / CONSUMED를 구분하며 RELEASED는 다시 반환하지 않는다.
         */
        removalService.releaseAll(
                eventId,
                removed.stream().map(Registration::getId).toList(),
                now
        );

        for (OrgRegistrationParticipantPricing item : existing) {
            OrgRegistrationModificationCandidateContext.ParticipantCandidate participant = item.candidate();
            Registration registration = participant.currentRegistration();

            registration.applyOrganizationModification(
                    participant.eventCategory(),
                    participant.souvenirJsons(),
                    participant.request(),
                    item.price().newContractAmount()
            );

            results.add(
                    new OrgRegistrationModificationResult.Member(
                            registration.getId(),
                            OrgRegistrationModificationResult.Change.EXISTING,
                            item.price(),
                            registration.getPaidAmount()
                    )
            );
        }

        for (Registration registration : removed) {
            RegistrationModificationPrice price =
                    RegistrationModificationPrice.forExisting(
                            registration.getContractAmount(),
                            BigDecimal.ZERO
                    );

            registration.removeFromOrganization();

            results.add(
                    new OrgRegistrationModificationResult.Member(
                            registration.getId(),
                            OrgRegistrationModificationResult.Change.REMOVED,
                            price,
                            registration.getPaidAmount()
                    )
            );
        }

        /*
         * 신규 확보 직후의 일시적 수량이 아니라,
         * 제거까지 반영한 최종 수량으로 전체 정원 마감을 판단한다.
         * 반환으로 여유가 생겼더라도 자동 재개는 하지 않는다.
         */
        if (!added.isEmpty()
                && capacityRepository.countFullTotalCapacities(
                eventId, CapacityType.EVENT_TOTAL
        ) > 0) {
            candidate.event().closeRegistrationForCapacity();
        }

        registrationRepository.flush();

        return new OrgRegistrationModificationResult(
                organizationId,
                results
        );
    }

    /**
     * 신규 후보를 기존 단체 생성 팩터리로 생성하고 최초 HELD를 확보한다.
     *
     * 생성용 DTO 변환은 정책검증을 다시 수행하기 위한 것이 아니라,
     * 검증 완료 값을 기존 Entity 생성 팩터리에 전달하기 위한 것이다.
     */
    private void createAndHoldNew(
            OrgRegistrationModificationCandidateContext candidate,
            List<OrgRegistrationParticipantPricing> added,
            List<OrgRegistrationModificationResult.Member> results,
            LocalDateTime now
    ) {
        if (added.isEmpty()) {
            return;
        }

        List<CapacityHoldRequest> holdRequests = new ArrayList<>();

        for (OrgRegistrationParticipantPricing item : added) {
            OrgRegistrationModificationCandidateContext.ParticipantCandidate participant
                    = item.candidate();
            OrgRegistrationModificationParticipantRequest request
                    = participant.request();

            OrgRegistrationParticipantRequest creationInput =
                    new OrgRegistrationParticipantRequest(
                            participant.eventCategory().getId(),
                            participant.souvenirJsons(),
                            request.name(),
                            request.phNum(),
                            request.birth(),
                            request.gender()
                    );

            Registration registration =
                    registrationRepository.save(
                            Registration.createForOrgPaymentMvp(
                                    candidate.event(),
                                    participant.eventCategory(),
                                    candidate.organization(),
                                    creationInput,
                                    participant.souvenirJsons(),
                                    item.price().newContractAmount()
                            )
                    );

            boolean child =
                    CapacityRequirementInput.fromCandidate(
                            participant.eventCategory().getId(),
                            request.birth(),
                            candidate.event().getStartDate().toLocalDate(),
                            participant.souvenirJsons()
                    ).child();

            holdRequests.add(
                    new CapacityHoldRequest(registration, child)
            );

            results.add(
                    new OrgRegistrationModificationResult.Member(
                            registration.getId(),
                            OrgRegistrationModificationResult.Change.ADDED,
                            item.price(),
                            registration.getPaidAmount()
                    )
            );
        }

        holdService.holdAll(
                candidate.event().getId(),
                holdRequests,
                now
        );
    }

    /**
     * 기존 구성원의 검증된 최종 필요량을 예약에 대응시켜 일괄 이동한다.
     *
     * RELEASED 기존 구성원의 재확보는 이 이동 경로에 포함하지 않는다.
     * 해당 상태는 기존 재확보 흐름에서 처리해야 한다.
     */
    private void moveExisting(
            String eventId,
            OrgRegistrationModificationCandidateContext candidate,
            List<OrgRegistrationParticipantPricing> existing,
            LocalDateTime now
    ) {
        if (existing.isEmpty()) {
            return;
        }

        Set<String> registrationIds =
                existing.stream()
                        .map(p -> p.candidate().currentRegistration().getId())
                        .collect(Collectors.toSet());

        List<Reservation> reservations =
                reservationRepository.findAllByRegistrationIds(
                        registrationIds
                );

        Map<String, Reservation> byRegistration = new HashMap<>();

        for (Reservation reservation : reservations) {
            String registrationId =
                    reservation.getRegistration().getId();

            if (!registrationIds.contains(registrationId)
                    || byRegistration.putIfAbsent(
                    registrationId, reservation
            ) != null) {
                throw new CustomException(
                        ErrorCode.INVALID_RESERVATION_ARGUMENT
                );
            }
        }

        if (!byRegistration.keySet().equals(registrationIds)) {
            throw new CustomException(ErrorCode.RESERVATION_NOT_FOUND);
        }

        List<CapacityRequirementInput> inputs =
                existing.stream()
                        .map(item -> {
                            OrgRegistrationModificationCandidateContext.ParticipantCandidate participant
                                    = item.candidate();

                            return CapacityRequirementInput.fromCandidate(
                                    participant.eventCategory().getId(),
                                    participant.request().birth(),
                                    candidate.event().getStartDate().toLocalDate(),
                                    participant.souvenirJsons()
                            );
                        })
                        .toList();

        List<Map<String, Integer>> quantities =
                requirementResolver.resolveAll(eventId, inputs);

        Map<String, Map<String, Integer>> newRequirements =
                new TreeMap<>();

        for (int index = 0; index < existing.size(); index++) {
            String registrationId =
                    existing.get(index).candidate()
                            .currentRegistration().getId();

            newRequirements.put(
                    byRegistration.get(registrationId).getId(),
                    quantities.get(index)
            );
        }

        List<CapacityRequirementDiff> diffs = diffService.compareAll(
                reservations,
                newRequirements
        );

        modificationService.moveAll(eventId, diffs, now);
    }
}
