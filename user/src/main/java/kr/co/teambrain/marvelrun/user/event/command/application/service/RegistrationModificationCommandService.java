package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
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
 * 본인확인·Payment 충돌 차단·정책·가격·Capacity·신청 상태·
 * 새 최초 주문 생성을 하나의 트랜잭션으로 처리한다.
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

    /**
     * 개인 신청 수정과 후속 금융 상태 처리를 함께 완료한다.
     */
    @Transactional
    public RegistrationModificationSettlementResult modifyPersonal(
            String eventId,
            String registrationId,
            RegistrationModificationRequest request
    ) {
        LocalDateTime now = serverTimeProvider.currentDateTime();

        RegistrationPersonalModificationResult result =
                personalService.modify(
                        eventId,
                        registrationId,
                        request,
                        now
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