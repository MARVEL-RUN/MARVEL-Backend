package kr.co.teambrain.marvelrun.user.event.command.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityRequirementDiff;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityRequirementInput;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.CapacityModificationService;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.CapacityRequirementResolver;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationCapacityService;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationCapacityDiffService;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationModificationCandidateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationPrice;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationPersonalModificationResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationModificationAccessValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationModificationCandidateValidator;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 개인 신청 수정의 정책·가격·Capacity·신청정보 반영을 연결한다.
 *
 * 수정 Use Case가 시작한 동일 트랜잭션 안에서만 실행한다.
 * 호출자는 Payment 충돌 차단과 금융 상태 처리를 함께 연결해야 한다.
 *
 * 이 서비스만 호출하여 외부 수정 요청을 완료 처리해서는 안 된다.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RegistrationPersonalModificationService {

    private final RegistrationCapacityService registrationCapacityService;

    private final RegistrationModificationAccessValidator accessValidator;
    private final RegistrationModificationCandidateValidator candidateValidator;
    private final RegistrationModificationPricingService modificationPricingService;

    private final ReservationCommandRepository reservationRepository;
    private final CapacityRequirementResolver capacityRequirementResolver;
    private final ReservationCapacityDiffService reservationCapacityDiffService;
    private final CapacityModificationService capacityModificationService;

    private final RegistrationCommandRepository registrationRepository;

    private final RegistrationModificationPaymentGuard paymentGuard;
    private final EntityManager entityManager;

    /**
     * 개인 신청의 변경 후보를 검증하고 실제 신청에 반영한다.
     *
     * now는 외부 수정 Use Case에서 한 번 구한 시각을 전달한다.
     * 최초 DB 조회 전에 대회를 잠그며, 이후 모든 처리는 같은 Tx에서 수행한다.
     *
     * Capacity 확보 실패 시 실제 Registration 필드는 변경하지 않는다.
     * 마지막 Registration flush 실패 시 먼저 이동한 Capacity도 롤백된다.
     */
    public RegistrationPersonalModificationResult modify(
            String eventId,
            String registrationId,
            RegistrationModificationRequest request,
            LocalDateTime now
    ) {
        return modify(eventId, registrationId, request, now, null);
    }

    /**
     * 사전 비교 version을 검증한 뒤 전체 수정을 수행한다.
     * Event → Payment 이후에만 Registration을 최신 잠금 조회하므로 잠금 순서를 뒤집지 않는다.
     * version이 달라졌으면 오래된 요청을 재적용하지 않고 전체 트랜잭션을 종료한다.
     */
    public RegistrationPersonalModificationResult modify(
            String eventId,
            String registrationId,
            RegistrationModificationRequest request,
            LocalDateTime now,
            Long comparedVersion
    ) {
        registrationCapacityService.lockEvent(eventId);

        /*
         * 승인 결과 반영과 경합할 수 있으므로
         * 전체 수정용 Registration 최신 조회보다 먼저 관련 Payment를 잠근다.
         */
        List<Payment> lockedPayments =
                paymentGuard.lockPersonal(
                        eventId,
                        registrationId
                );

        if (comparedVersion != null) {
            Registration current = registrationRepository.findById(registrationId)
                    .orElseThrow(() -> new CustomException(ErrorCode.REGISTRATION_NOT_FOUND));
            entityManager.refresh(current, LockModeType.PESSIMISTIC_WRITE);
            if (!Objects.equals(comparedVersion, current.getVersion())) {
                throw new CustomException(ErrorCode.CONCURRENT_MODIFICATION);
            }
        }

        RegistrationModificationAccessContext access =
                accessValidator.validate(
                        eventId,
                        registrationId,
                        request,
                        now
                );

        /*
         * 본인확인 성공 이후에만 주문 상태를 변경한다.
         * 이후 후보 검증 실패 시 무효화도 함께 롤백된다.
         */
        paymentGuard.prepareLockedPayments(lockedPayments);

        RegistrationModificationCandidateContext candidate = candidateValidator.validate(access);

        Registration registration =
                candidate.currentRegistration();

        RegistrationModificationPrice price =
                modificationPricingService.repricePersonal(candidate);

        BigDecimal paidAmount =
                registration.getPaidAmount();

        Reservation reservation =
                reservationRepository.findByRegistration_Id(
                        registration.getId()
                ).orElseThrow(
                        () -> new CustomException(
                                ErrorCode.RESERVATION_NOT_FOUND
                        )
                );

        CapacityRequirementInput requirementInput =
                CapacityRequirementInput.fromCandidate(
                        candidate.eventCategory().getId(),
                        candidate.request().birth(),
                        candidate.event().getStartDate().toLocalDate(),
                        candidate.souvenirJsons()
                );

        Map<String, Integer> newRequirements =
                capacityRequirementResolver.resolveAll(
                        eventId,
                        List.of(requirementInput)
                ).get(0);

        List<CapacityRequirementDiff> diffs =
                reservationCapacityDiffService.compareAll(
                        List.of(reservation),
                        Map.of(
                                reservation.getId(),
                                newRequirements
                        )
                );

        /*
         * 검증·계산 중에는 실제 Registration을 변경하지 않는다.
         * 필요한 Capacity 이동에 성공한 뒤 후보를 반영한다.
         */
        capacityModificationService.moveAll(
                eventId,
                diffs,
                now
        );

        registration.applyPersonalModification(
                candidate.eventCategory(),
                candidate.souvenirJsons(),
                candidate.request(),
                price.newContractAmount()
        );

        /*
         * 관리 중인 Entity이므로 save()로 다시 병합하지 않는다.
         * @Version 충돌과 DB 제약 오류를 현재 단계에서 확인한다.
         *
         * flush는 커밋이 아니며 후속 금융 처리 실패 시에도 함께 롤백된다.
         */
        registrationRepository.flush();

        return new RegistrationPersonalModificationResult(
                registration.getId(),
                price,
                paidAmount
        );
    }


}
