package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationReleaseService;

import java.util.HashSet;
import java.util.Set;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationCapacityService;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationCreateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.OrgRegistrationCreateResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationApplyValidator;
import kr.co.teambrain.marvelrun.user.event.command.repository.OrganizationCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
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

    private final PaymentCreator
            paymentCreator;

    private final ServerTimeProvider serverTimeProvider;


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

        Payment payment =
                paymentCreator.createInitialPayment(
                        savedOrganization,
                        totalContractAmount,
                        correlationId
                );

        return OrgRegistrationCreateResponse.from(
                savedOrganization,
                savedRegistrations,
                payment
        );
    }

    /**
     * 기존 단체의 최초 미결제 참가자들에 대한 새 결제 주문을 생성한다.
     *
     * 현재 단체 구성원을 대상으로 하며 참가자 추가나 신청 수정은 수행하지 않는다.
     * HELD 참가자의 확보는 유지하고 RELEASED 참가자만 재확보한다.
     *
     * 호출자는 해당 단체의 작업 권한을 먼저 검증해야 한다.
     * 모든 참가자의 확보 준비와 새 단체 주문 생성은 동일 트랜잭션에서 처리한다.
     */
    @Transactional
    public OrgRegistrationCreateResponse prepareRepayment(
            String eventId,
            String organizationId
    ) {

        registrationCapacityService.lockEvent(eventId);

        LocalDateTime now =
                serverTimeProvider.currentDateTime();

        Organization organization =
                organizationCommandRepository.findById(organizationId)
                        .orElseThrow(
                                () -> new CustomException(
                                        ErrorCode.PAYMENT_NOT_CONFIRMABLE,
                                        " 재결제 대상 단체를 찾을 수 없습니다."
                                )
                        );

        if (!eventId.equals(organization.getEvent().getId())) {
            throw new CustomException(
                    ErrorCode.PAYMENT_NOT_CONFIRMABLE,
                    " 재결제 대상 단체의 대회가 일치하지 않습니다."
            );
        }

        List<Registration> registrations =
                registrationCommandRepository.findAllByOrganization_Id(
                        organizationId
                );

        /*
         * 전체 대상이 최초 미결제 신청인지 검증하고,
         * 반환된 예약에 대해서만 재확보한다.
         *
         * 일부 구성원이 결제 완료·삭제 상태인 단체를
         * 임의로 제외하여 다른 금액의 주문으로 만들지 않는다.
         */
        registrationCapacityService.prepareForRepayment(
                organization.getEvent(),
                registrations,
                now
        );

        /*
         * 개별 계약금액은 재산정하지 않는다.
         * 기존 메서드로 현재 결제 대상의 계약금액을 합산한다.
         */
        BigDecimal totalContractAmount =
                calculateTotalContractAmount(registrations);

        Payment payment =
                paymentCreator.createInitialPayment(
                        organization,
                        totalContractAmount,
                        UUID.randomUUID().toString()
                );

        return OrgRegistrationCreateResponse.from(
                organization,
                registrations,
                payment
        );
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

            BigDecimal contractAmount =
                    participantContext
                            .eventCategory()
                            .getAmount();

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