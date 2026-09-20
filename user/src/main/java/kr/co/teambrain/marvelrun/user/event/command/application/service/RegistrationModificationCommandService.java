package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationModificationAccessValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationPersonalInformationValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.OrgRegistrationModificationResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationPersonalModificationResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationModificationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 신청 수정 전체의 트랜잭션 경계를 제공한다.
 *
 * 개인 요청은 대상 접근 확인 후 자원 영향에 따라 분기한다.
 * 개인정보 정정은 결제·예약·정원을 조회하지 않고, 전체 수정만 기존 정산을 수행한다.
 *
 * 외부 Toss 승인·환불 API는 호출하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RegistrationModificationCommandService {

    private final RegistrationPersonalModificationService personalService;
    private final OrgRegistrationModificationService organizationService;
    private final RegistrationModificationSettlementService settlementService;
    private final ServerTimeProvider serverTimeProvider;
    private final RegistrationModificationAccessValidator accessValidator;
    private final RegistrationPersonalInformationValidator informationValidator;
    private final RegistrationModificationClassifier classifier;
    private final RegistrationPersonalInformationService informationService;

    /**
     * 개인 신청을 잠금용 부가 조회 전에 분류하고 전체 수정에만 금융 상태 처리를 연결한다.
     */
    @Transactional
    public RegistrationModificationSettlementResult modifyPersonal(
            String eventId,
            String registrationId,
            RegistrationModificationRequest request
    ) {
        LocalDateTime now = serverTimeProvider.currentDateTime();

        informationValidator.validateInput(request);
        RegistrationModificationAccessContext access = accessValidator.validate(eventId, registrationId, request, now);
        RegistrationModificationClassifier.Change change = classifier.classifyPersonal(access.registration(), request);
        if (change != RegistrationModificationClassifier.Change.FULL) {
            return informationService.modify(access, change);
        }

        RegistrationPersonalModificationResult result =
                personalService.modify(
                        eventId,
                        registrationId,
                        request,
                        now,
                        access.registration().getVersion()
                );

        return settlementService.settle(
                eventId,
                null,
                List.of(result.registrationId()),
                now
        );
    }

    /**
     * 단체의 기존·신규·제거 구성원 전체를 같은 트랜잭션에서 처리한다.
     */
    @Transactional
    public RegistrationModificationSettlementResult modifyOrganization(
            String eventId,
            String organizationId,
            OrgRegistrationModificationRequest request
    ) {
        LocalDateTime now = serverTimeProvider.currentDateTime();

        OrgRegistrationModificationResult result =
                organizationService.modify(
                        eventId,
                        organizationId,
                        request,
                        now
                );

        List<String> registrationIds =
                result.members().stream()
                        .map(OrgRegistrationModificationResult.Member::registrationId)
                        .toList();

        return settlementService.settle(
                eventId,
                organizationId,
                registrationIds,
                now
        );
    }
}
