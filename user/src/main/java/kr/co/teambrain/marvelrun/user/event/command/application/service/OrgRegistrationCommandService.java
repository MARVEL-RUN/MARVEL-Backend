package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationReleaseService;

import java.util.HashSet;
import java.util.Set;

import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationCapacityService;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationCreateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.OrgNameExistResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.OrgRegistrationCreateResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationApplyValidator;
import kr.co.teambrain.marvelrun.user.event.command.repository.OrganizationCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentAllocationCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentAllocationCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentAllocationTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;


/**
 * 단체와 구성원 신청, 자원 예약, 단체 최초 결제를 생성한다.
 *
 * 구성원 전체의 필요 수량을 합산하여 한 번에 확보한다.
 * 한 자원이라도 부족하면 단체 전체의 생성을 롤백한다.
 */
@Service
@RequiredArgsConstructor
public class OrgRegistrationCommandService {

    private final RegistrationCapacityService registrationCapacityService;
    private final ReservationReleaseService reservationReleaseService;

    private final OrgRegistrationApplyValidator
            orgRegistrationApplyValidator;

    private final OrganizationCommandRepository
            organizationCommandRepository;

    private final RegistrationCommandRepository
            registrationCommandRepository;

    private final RegistrationPricingService
            registrationPricingService;

    private final PaymentCreator
            paymentCreator;

    private final PaymentAllocationCreator
            paymentAllocationCreator;

    private final ServerTimeProvider serverTimeProvider;

    private final PaymentCommandRepository paymentCommandRepository;

    private final PaymentAllocationCommandRepository paymentAllocationCommandRepository;


    @Transactional(readOnly = true)
    public OrgNameExistResponse checkExistsGroupInfo(String groupName, String loginId, String eventId) {
        return OrgNameExistResponse.fromRawValue(
                groupName,
                organizationCommandRepository.existsByGroupNameAndEventId(groupName, eventId),
                loginId,
                organizationCommandRepository.existsByLoginIdAndEventId(groupName, eventId)
        );
    }

    /**
     * 단체 구성원 전체의 신청과 자원을 하나의 트랜잭션으로 처리한다.
     *
     * 대회 잠금과 정책 검증 후 단체 및 신청을 저장하고,
     * 구성원 전체의 정원·기념품을 일괄 확보한다.
     *
     * 정원 부족 시 일부 구성원만 접수하지 않고 전체를 롤백한다.
     * 최초 Payment는 단체 대상으로 한 건만 생성한다.
     */
    @Transactional
    public OrgRegistrationCreateResponse register(
            String eventId,
            OrgRegistrationCreateRequest request
    ) {
        LocalDateTime now = serverTimeProvider.currentDateTime();

        registrationCapacityService.lockEvent(eventId);

        OrgRegistrationCreateContext context =
                orgRegistrationApplyValidator.validate(
                        eventId,
                        request,
                        now
                );

        Event event = context.event();

        Organization organization =
                createOrganization(event, request);

        Organization savedOrganization =
                organizationCommandRepository.save(organization);

        List<Registration> registrations =
                createRegistrations(
                        savedOrganization,
                        context
                );

        List<Registration> savedRegistrations =
                registrationCommandRepository.saveAll(registrations);

        registrationCapacityService.holdAndCloseIfFull(
                event,
                savedRegistrations,
                now
        );

        BigDecimal totalContractAmount =
                calculateTotalContractAmount(savedRegistrations);

        String correlationId = UUID.randomUUID().toString();


        /*
         * 단체 전체 최초 Payment는 딱 1건 생성한다.
         *
         * registration = null
         * organization = savedOrganization
         * amount = 전체 Registration.contractAmount 합계
         */
        Payment payment =
                paymentCreator.createInitialPayment(
                        savedOrganization,
                        totalContractAmount,
                        correlationId
                );

        /*
         * 단체 Payment 금액을 실제 참가자 Registration별로 귀속한다.
         *
         * 각 Allocation은 해당 Payment 생성 당시의
         * Registration.contractAmount를 보존한다.
         *
         * PaymentAllocationCreator 내부에서
         * 전체 Allocation 합과 Payment.amount 일치 여부도 검증한다.
         */
        paymentAllocationCreator.create(
                payment,
                savedRegistrations.stream()
                        .map(
                                registration ->
                                        new PaymentAllocationTarget(
                                                registration,
                                                registration.getContractAmount()
                                        )
                        )
                        .toList()
        );

        return OrgRegistrationCreateResponse.from(
                savedOrganization,
                savedRegistrations,
                payment
        );
    }

    // [TO-BE] OrgRegistrationCommandService.java 내 prepareRepayment 수정안 (2-F)

    @Transactional
    public OrgRegistrationCreateResponse prepareRepayment(
            String eventId,
            String organizationId,
            String failedPaymentId // [TO-BE] 실패한 결제 ID를 받아서 일부(B)만 재결제 가능하도록 구성
    ) {

        registrationCapacityService.lockEvent(eventId);

        LocalDateTime now = serverTimeProvider.currentDateTime();

        Payment failedPayment = paymentCommandRepository.findById(failedPaymentId)
                .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_NOT_CONFIRMABLE, " 재결제 대상 주문을 찾을 수 없습니다."));

        // 최초 재결제 진입점에서 추가·혼합 주문 일부만 떼어 새 결제로 만들지 않는다.
        if (failedPayment.getPurpose()
                != kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose.REGISTRATION_TRY) {
            throw new CustomException(ErrorCode.PAYMENT_NOT_CONFIRMABLE,
                    " 최초 참가비 주문만 이 재결제 경로에서 처리할 수 있습니다.");
        }

        // 1. 명확히 차단해야 할 상태(CONFIRMING, UNKNOWN, COMPLETED)만 걸러내도록 수정
        PaymentProcessStatus status = failedPayment.getProcessStatus();
        if (status == PaymentProcessStatus.CONFIRMING || status == PaymentProcessStatus.UNKNOWN || status == PaymentProcessStatus.COMPLETED) {
            throw new CustomException(ErrorCode.PAYMENT_NOT_CONFIRMABLE, " 처리 중이거나 완료된 주문은 재결제할 수 없습니다.");
        }

        Organization organization = failedPayment.getOrganization();

        if (organization == null || !organization.getId().equals(organizationId)) {
            throw new CustomException(ErrorCode.PAYMENT_NOT_CONFIRMABLE, " 결제 주문의 대상 단체가 일치하지 않습니다.");
        }

        if (!eventId.equals(organization.getEvent().getId())) {
            throw new CustomException(ErrorCode.PAYMENT_NOT_CONFIRMABLE, " 재결제 대상 단체의 대회가 일치하지 않습니다.");
        }

        // 2. 전체 명단 조회가 아닌, 실패한 주문(failedPayment)에 묶여 있던 Allocation 대상만 추출
        List<PaymentAllocation> failedAllocations = paymentAllocationCommandRepository
                .findAllByPayment_IdOrderByRegistration_IdAsc(failedPayment.getId());

        // 추출된 대상 중 현재 상태가 미결제(PAYMENT_PENDING)인 사람(B)만 필터링
        List<Registration> retryRegistrations = failedAllocations.stream()
                .map(PaymentAllocation::getRegistration)
                .filter(reg -> reg.getStatus() == RegistrationStatus.PAYMENT_PENDING)
                .toList();

        // 3. 추출된 일부 대상(B)에 대해서만 확보 준비
        registrationCapacityService.prepareForRepayment(
                organization.getEvent(),
                retryRegistrations,
                now
        );

        // 4. 추출된 일부 대상(B)의 계약금 합산
        BigDecimal totalContractAmount = calculateTotalContractAmount(retryRegistrations);

        Payment payment = paymentCreator.createInitialPayment(
                organization,
                totalContractAmount,
                UUID.randomUUID().toString()
        );

        paymentAllocationCreator.create(
                payment,
                retryRegistrations.stream()
                        .map(registration -> new PaymentAllocationTarget(registration, registration.getContractAmount()))
                        .toList()
        );

        return OrgRegistrationCreateResponse.from(organization, retryRegistrations, payment);
    }

    /**
     * 단체에서 지정한 미결제 참가자들의 확보 수량을 반환한다.
     *
     * 전달받은 참가자들이 요청한 단체와 대회에 속하는지 검증한다.
     * 관련 단체 주문은 무효화하되 수량은 지정한 참가자들만 반환한다.
     *
     * 신청 삭제나 단체 구성원 제거는 수행하지 않는다.
     * 호출자는 해당 단체의 작업 권한을 검증해야 한다.
     */
    @Transactional
    public void releaseReservations(
            String eventId,
            String organizationId,
            List<String> registrationIds
    ) {

        if (registrationIds.isEmpty()) {
            throw new CustomException(
                    ErrorCode.INVALID_RESERVATION_ARGUMENT,
                    " 반환 대상 신청 목록이 비어 있습니다."
            );
        }

        registrationCapacityService.lockEvent(eventId);

        LocalDateTime now =
                serverTimeProvider.currentDateTime();

        Organization organization =
                organizationCommandRepository.findById(organizationId)
                        .orElseThrow(
                                () -> new CustomException(
                                        ErrorCode.INVALID_RESERVATION_ARGUMENT,
                                        " 반환 대상 단체를 찾을 수 없습니다."
                                )
                        );

        if (!eventId.equals(organization.getEvent().getId())) {
            throw new CustomException(
                    ErrorCode.INVALID_RESERVATION_ARGUMENT,
                    " 반환 대상 단체의 대회가 일치하지 않습니다."
            );
        }

        Set<String> uniqueIds =
                new HashSet<>(registrationIds);

        List<Registration> registrations =
                registrationCommandRepository.findAllById(uniqueIds);

        if (registrations.size() != uniqueIds.size()) {
            throw new CustomException(
                    ErrorCode.INVALID_RESERVATION_ARGUMENT,
                    " 반환 대상 신청 중 존재하지 않는 신청이 있습니다."
            );
        }

        for (Registration registration : registrations) {

            Organization participantOrganization =
                    registration.getOrganization();

            /*
             * organization은 개인 신청에서 null일 수 있으므로 확인한다.
             * 요청 목록에 다른 단체 또는 개인 신청이 섞이면 전체를 거절한다.
             */
            if (
                    participantOrganization == null
                            || !organizationId.equals(
                            participantOrganization.getId()
                    )
                            || !eventId.equals(
                            registration.getEvent().getId()
                    )
                            || registration.isSoftDeleted()
                            || registration.getStatus()
                            != RegistrationStatus.PAYMENT_PENDING
            ) {
                throw new CustomException(
                        ErrorCode.RESERVATION_STATE_CONFLICT,
                        " 해당 단체의 반환 가능한 미결제 신청이 아닙니다."
                                + " registrationId=" + registration.getId()
                );
            }
        }

        /*
         * 단체 전체로 대상을 확대하지 않는다.
         * 관련 주문은 무효화하고, 요청한 참가자의 수량만 반환한다.
         */
        reservationReleaseService.releaseUnpaid(
                eventId,
                List.copyOf(uniqueIds),
                now
        );
    }

    @Transactional()


    /**
     * 단체 자체 정보 생성.
     *
     * Organization 규모가 크지 않고
     * 현재 별도 use-case가 없으므로
     * OrgRegistrationCommandService에서 직접 생성한다.
     */
    private Organization createOrganization(
            Event event,
            OrgRegistrationCreateRequest request
    ) {

        String email =
                request.profile()
                        .email() == null
                        ? ""
                        : request.profile()
                        .email();

        return Organization.builder()
                .loginId(
                        request.account()
                                .organizationLoginId()
                )
                .password(
                        request.account()
                                .organizationPassword()
                )
                .groupName(
                        request.account()
                                .organizationName()
                )
                .leaderName(
                        request.profile()
                                .leaderName()
                )
                .leaderBirth(
                        request.profile()
                                .birth()
                                .toString()
                )
                .leaderPhNum(
                        request.profile()
                                .phNum()
                )
                .guardianConsent(
                        Boolean.TRUE.equals(
                                request.profile()
                                        .guardianConsent()
                        )
                )
                .email(
                        email
                )
                .event(
                        event
                )
                .address(
                        request.profile()
                                .address()
                )
                .addressDetail(
                        request.profile()
                                .addressDetail()
                )
                .build();
    }


    /**
     * 단체 참가자 N명을
     * 개인 신청과 동일한 Registration Entity로 생성한다.
     *
     * 단체 신청이므로 Registration.organization만
     * 생성된 Organization을 가리킨다.
     *
     * 각 참가자의 contractAmount는 개인 신청과 동일한
     * RegistrationPricingService를 통해 서버에서 계산한다.
     *
     * password / address / addressBase 등의 단체 전용 차이는
     * Registration.createForOrgPaymentMvp() 내부에서 처리한다.
     */
    private List<Registration> createRegistrations(
            Organization organization,
            OrgRegistrationCreateContext context
    ) {
        List<Registration> registrations =
                new ArrayList<>(
                        context.registrations().size()
                );

        for (OrgRegistrationCreateContext.ParticipantContext participantContext
                : context.registrations()) {

            /*
             * 검증 완료된 참가자 정보를 기준으로
             * 개인 신청과 동일한 서버 Pricing을 사용하여
             * 서버에서 참가자별 최종 계약금액을 계산한다.
             *
             * Client 금액은 사용하지 않으며,
             * 개인 신청과 동일한 RegistrationPricingService를 사용한다.
             */
            BigDecimal contractAmount =
                    registrationPricingService.calculateContractAmount(
                            context.event(),
                            participantContext.eventCategory(),
                            participantContext.request().birth()
                    );

            Registration registration =
                    Registration.createForOrgPaymentMvp(
                            context.event(),
                            participantContext.eventCategory(),
                            organization,
                            participantContext.request(),
                            participantContext.souvenirJsons(),
                            contractAmount
                    );

            registrations.add(
                    registration
            );
        }

        return registrations;
    }


    /**
     * 단체 최초 결제금액 계산.
     *
     * Payment.amount
     * =
     * 모든 Registration.contractAmount 합계.
     */
    private BigDecimal calculateTotalContractAmount(
            List<Registration> registrations
    ) {

        return registrations.stream()
                .map(
                        Registration::getContractAmount
                )
                .reduce(
                        BigDecimal.ZERO,
                        BigDecimal::add
                );
    }
}