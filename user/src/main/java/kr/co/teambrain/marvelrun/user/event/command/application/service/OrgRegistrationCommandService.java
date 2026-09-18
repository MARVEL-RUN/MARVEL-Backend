package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationCapacityService;
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