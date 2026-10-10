package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationModificationAccessValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationPersonalInformationValidator;
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
import java.util.ArrayList;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyService;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyModels.*;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationModificationParticipantRequest;

/**
 * 신청 수정 전체의 트랜잭션 경계를 제공한다.
 *
 * 개인·단체 요청은 대상 접근 확인 후 자원 영향에 따라 분기한다.
 * 개인정보 정정은 결제·예약·정원을 조회하지 않고, 전체 수정만 기존 정산을 수행한다.
 *
 * 외부 Toss 승인·환불 API는 호출하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RegistrationModificationTransactionService {

    private final RegistrationPersonalModificationService personalService;
    private final OrgRegistrationModificationService organizationService;
    private final RegistrationModificationSettlementService settlementService;
    private final ServerTimeProvider serverTimeProvider;
    private final RegistrationModificationAccessValidator accessValidator;
    private final RegistrationPersonalInformationValidator informationValidator;
    private final RegistrationModificationClassifier classifier;
    private final RegistrationPersonalInformationService informationService;
    private final OrgRegistrationModificationAccessValidator organizationAccessValidator;
    private final OrgRegistrationPersonalInformationValidator organizationInformationValidator;
    private final OrgRegistrationPersonalInformationService organizationInformationService;
    private final RegistrationActionPolicyService actionPolicies;

    /**
     * 개인 신청을 잠금용 부가 조회 전에 분류하고 전체 수정에만 금융 상태 처리를 연결한다.
     */
    @Transactional
    public RegistrationModificationSettlementResult modifyPersonalRegistration(
            String eventId,
            String registrationId,
            RegistrationModificationRequest request
    ) {
        LocalDateTime now = serverTimeProvider.currentDateTime();

        informationValidator.validateInput(request);
        RegistrationModificationAccessContext access = accessValidator.validate(eventId, registrationId, request, now);
        RegistrationModificationClassifier.Change change = classifier.classifyPersonal(access.registration(), request);
        List<Policy> policies = actionPolicies.loadEnabledRegistrationActionPolicies(eventId);
        actionPolicies.validateRegistrationActionPolicy(access.event(), access.registration(), Action.MODIFY, now, policies);
        if (change != RegistrationModificationClassifier.Change.FULL) {
            return informationService.modify(access, change);
        }

        RegistrationPersonalModificationResult result =
                personalService.modifyPersonalRegistration(
                        eventId,
                        registrationId,
                        request,
                        now,
                        access.registration().getVersion()
                );

        return settlementService.settleRegistrationModification(
                eventId,
                null,
                List.of(result.registrationId()),
                now, policies
        );
    }

    /**
     * 단체 전체 목록을 자원 조회·잠금 전에 분류하며 추가·제거·정책 영향이 있는 요청만 전체 수정한다.
     */
    @Transactional
    public RegistrationModificationSettlementResult modifyOrganizationRegistration(
            String eventId,
            String organizationId,
            OrgRegistrationModificationRequest request
    ) {
        LocalDateTime now = serverTimeProvider.currentDateTime();

        organizationInformationValidator.validateInput(request);
        OrgRegistrationModificationAccessContext access = organizationAccessValidator.validate(
                eventId, organizationId, request, now);
        RegistrationModificationClassifier.Change change = classifier.classifyOrganization(
                access.currentRegistrations(), request);
        List<Policy> policies = actionPolicies.loadEnabledRegistrationActionPolicies(eventId);
        validateOrganizationActions(access, policies);
        if (change != RegistrationModificationClassifier.Change.FULL) {
            return organizationInformationService.modify(access, change);
        }

        OrgRegistrationModificationResult result =
                organizationService.modifyOrganizationRegistration(
                        eventId,
                        organizationId,
                        request,
                        now,
                        access
                );

        List<String> registrationIds =
                result.members().stream()
                        .map(OrgRegistrationModificationResult.Member::registrationId)
                        .toList();

        return settlementService.settleRegistrationModification(
                eventId,
                organizationId,
                registrationIds,
                now, policies
        );
    }

    /** 요청에 실제 포함된 공통정보·인원별 변경·삭제·추가를 서로 독립적으로 검증한다. */
    private void validateOrganizationActions(OrgRegistrationModificationAccessContext access, List<Policy> policies) {
        boolean commonChanged = classifier.organizationProfileChanged(access.organization(), access.request());
        if (access.request().registrations().stream().anyMatch(r -> r.registrationId() == null)) {
            RegistrationActionPolicyService.validateGlobalRegistrationActionPolicy(access.event(), Action.ADD_MEMBER, access.now());
        }
        // 같은 작업의 대상을 모아 단체 정책 우선순위로 한 번 검증한다.
        List<Registration> modifiedMembers = new ArrayList<>();
        List<Registration> deletedMembers = new ArrayList<>();
        for (Registration member : access.currentRegistrations()) {
            OrgRegistrationModificationParticipantRequest requested = access.request().registrations().stream()
                    .filter(r -> member.getId().equals(r.registrationId())).findFirst().orElse(null);
            if (commonChanged || (requested != null && classifier.classifyOrganizationParticipant(member, requested)
                    != RegistrationModificationClassifier.Change.NONE)) {
                modifiedMembers.add(member);
            }
            if (requested == null) {
                deletedMembers.add(member);
            }
        }
        if (!modifiedMembers.isEmpty()) {
            actionPolicies.validateRegistrationActionsPolicy(access.event(), modifiedMembers, Action.MODIFY,
                    access.now(), policies, commonChanged);
        }
        if (!deletedMembers.isEmpty()) {
            actionPolicies.validateRegistrationActionsPolicy(access.event(), deletedMembers, Action.DELETE_MEMBER,
                    access.now(), policies, false);
        }
    }
}
