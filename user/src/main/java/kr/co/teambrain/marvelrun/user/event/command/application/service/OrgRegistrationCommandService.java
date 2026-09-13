package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationCreateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.OrgRegistrationCreateResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationApplyValidator;
import kr.co.teambrain.marvelrun.user.event.command.repository.OrganizationCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.domain.Payment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrgRegistrationCommandService {

    private static final long PAYMENT_PENDING_MINUTES = 30L;


    private final OrgRegistrationApplyValidator
            orgRegistrationApplyValidator;

    private final OrganizationCommandRepository
            organizationCommandRepository;

    private final RegistrationCommandRepository
            registrationCommandRepository;

    private final PaymentCreator
            paymentCreator;


    /**
     * 단체 신청 생성.
     *
     * Organization 1건,
     * Registration N건,
     * Organization 대상 최초 Payment 1건을
     * 하나의 Transaction에서 생성한다.
     */
    @Transactional
    public OrgRegistrationCreateResponse register(
            String eventId,
            OrgRegistrationCreateRequest request
    ) {

        /*
         * Event / Category / Souvenir /
         * 동일 참가자 중복 신청 여부 검증.
         */
        OrgRegistrationCreateContext context =
                orgRegistrationApplyValidator.validate(
                        eventId,
                        request
                );


        Event event =
                context.event();


        /*
         * Organization 생성.
         *
         * MVP에서는 단체 비밀번호를
         * 암호화하지 않고 요청값 그대로 저장한다.
         */
        Organization organization =
                createOrganization(
                        event,
                        request
                );


        Organization savedOrganization =
                organizationCommandRepository.save(
                        organization
                );


        /*
         * 하나의 단체 신청으로 생성되는 Registration들은
         * 동일한 최초 결제 만료시각을 사용한다.
         */
        LocalDateTime expiresAt =
                calculatePaymentExpiresAt(
                        event
                );


        /*
         * 검증 완료된 ParticipantContext들을
         * 실제 Registration Entity로 변환한다.
         */
        List<Registration> registrations =
                createRegistrations(
                        savedOrganization,
                        context,
                        expiresAt
                );


        List<Registration> savedRegistrations =
                registrationCommandRepository.saveAll(
                        registrations
                );


        /*
         * 단체 결제금액은 Client가 결정하지 않는다.
         *
         * 실제 생성된 각 Registration의
         * contractAmount 합계가 Source of Truth다.
         */
        BigDecimal totalContractAmount =
                calculateTotalContractAmount(
                        savedRegistrations
                );


        /*
         * 단체 신청 생성 Transaction과
         * Payment 생성 로그를 연결하기 위한 correlationId.
         */
        String correlationId =
                UUID.randomUUID()
                        .toString();


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
         * Frontend가 바로 Toss 결제 인증을 시작할 수 있도록
         * orderId / orderName / paymentAmount를 반환한다.
         */
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
                .account(
                        request.account()
                                .organizationAccount()
                )
                .accountPassword(
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
            OrgRegistrationCreateContext context,
            LocalDateTime expiresAt
    ) {

        List<Registration> registrations =
                new ArrayList<>(
                        context.registrations()
                                .size()
                );


        for (
                OrgRegistrationCreateContext.ParticipantContext participantContext
                : context.registrations()
        ) {

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
                            contractAmount,
                            expiresAt
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


    /**
     * 개인 신청과 동일한 결제 대기 만료 정책.
     *
     * 신청 생성시각 + 30분과
     * Event.paymentDeadline 중 더 이른 값을 사용한다.
     */
    private LocalDateTime calculatePaymentExpiresAt(
            Event event
    ) {

        LocalDateTime pendingExpiresAt =
                LocalDateTime.now()
                        .plusMinutes(
                                PAYMENT_PENDING_MINUTES
                        );


        if (
                pendingExpiresAt.isAfter(
                        event.getPaymentDeadline()
                )
        ) {

            return event.getPaymentDeadline();
        }


        return pendingExpiresAt;
    }
}