package kr.co.teambrain.marvelrun.user.event.query.service;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyModels;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyService;
import kr.co.teambrain.marvelrun.user.event.query.assembler.RegistrationOptionResponseAssembler;
import kr.co.teambrain.marvelrun.user.event.query.dto.response.RegistrationOptionResponse;
import kr.co.teambrain.marvelrun.user.discount.query.DiscountQueryService;
import kr.co.teambrain.marvelrun.user.event.query.repository.EventCategorySouvenirQueryRepository;
import kr.co.teambrain.marvelrun.user.event.query.repository.EventQueryRepository;
import kr.co.teambrain.marvelrun.user.event.query.repository.projection.RegistrationOptionProjection;
import lombok.RequiredArgsConstructor;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategoryStartGroup;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryStartGroupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 종목·기념품 선택지와 현재 요청시각의 신규 접수 정책을 함께 조회한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventQueryService {
    private final DiscountQueryService discountQueries;
    private final EventQueryRepository eventQueryRepository;
    private final EventCategorySouvenirQueryRepository eventCategorySouvenirQueryRepository;
    private final RegistrationOptionResponseAssembler registrationOptionResponseAssembler;
    private final RegistrationActionPolicyService actionPolicies;
    private final ServerTimeProvider serverTimeProvider;
    private final EventCategoryStartGroupRepository startGroupRepository;

    /** 정책 차단을 조회 예외로 처리하지 않고 모든 옵션과 allowed-reason을 반환한다. */
    public RegistrationOptionResponse getRegistrationOptions(String eventId) {
        // 존재 확인과 전역 정책 입력 확보를 한 번의 대회 조회로 처리한다.
        LocalDateTime now = serverTimeProvider.currentDateTime();
        Event event = eventQueryRepository.findById(eventId)
                .orElseThrow(() -> new CustomException(ErrorCode.EVENT_NOT_FOUND));
        RegistrationActionPolicyModels.Decision decision = actionPolicies.evaluateNewRegistrationPolicy(event, now);

        // 신청 제한 상태라도 기존 종목·기념품 조회와 조립을 정상 수행한다.
        List<RegistrationOptionProjection> projections =
                eventCategorySouvenirQueryRepository.findRegistrationOptionsProjectionByEventId(eventId);

        // 그룹 기능 임시 중단: 선택지에서 그룹을 읽거나 노출하지 않는다.
        List<EventCategoryStartGroup> startGroups = List.of();

        return discountQueries.appendInternalPricingOptions(eventId,
                registrationOptionResponseAssembler.assemble(projections, decision, startGroups));
    }
}
