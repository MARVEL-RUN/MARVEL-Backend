package kr.co.teambrain.marvelrun.user.event.query.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationActionType;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationItem;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationEntryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationItemCommandRepository;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyEvaluator;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyModels.Action;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyModels.RegistrationActionPolicyInput;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyService;
import kr.co.teambrain.marvelrun.user.event.query.dto.ParticipantOrderResponse;
import kr.co.teambrain.marvelrun.user.event.query.dto.RegistrationQueryResponse;
import kr.co.teambrain.marvelrun.user.event.query.repository.RegistrationQueryData;
import kr.co.teambrain.marvelrun.user.event.query.repository.RegistrationQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 로그인한 주문자의 마이페이지 주문 조회. 토큰의 주문자 키로 주문을 찾으므로 본인확인 값을 다시 받지 않는다.
 * 주문 본문은 공개 신청 조회와 같은 규칙으로 조립하고, 구매 줄·받는 사람·마감 시각을 더한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ParticipantOrderQueryService {

    private final RegistrationQueryRepository repository;
    private final RegistrationQueryService registrationQueries;
    private final RegistrationItemCommandRepository itemRepository;
    private final RegistrationEntryCommandRepository entryRepository;
    private final RegistrationActionPolicyService actionPolicies;
    private final ServerTimeProvider time;
    private final RegistrationActionPolicyEvaluator policyEvaluator = new RegistrationActionPolicyEvaluator();

    /** 주문자의 주문 전부(취소 포함). 살아 있는 주문, 최근 신청 순이다. */
    public List<ParticipantOrderResponse> findOrders(String ordererKey) {
        return assemble(repository.orderer(ordererKey));
    }

    /** 주문 하나. 이 주문자의 주문이 아니면 없는 주문과 같은 오류로 응답한다. */
    public ParticipantOrderResponse findOrder(String registrationId, String ordererKey) {
        List<RegistrationQueryData.Member> own = repository.orderer(ordererKey).stream()
                .filter(member -> member.id().equals(registrationId))
                .toList();
        if (own.isEmpty()) {
            throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED,
                    " 토큰 주문자의 주문이 아님 registrationId=" + registrationId);
        }
        return assemble(own).getFirst();
    }

    private List<ParticipantOrderResponse> assemble(List<RegistrationQueryData.Member> members) {
        if (members.isEmpty()) {
            return List.of();
        }
        LocalDateTime now = time.currentDateTime();
        // 주문자 키는 한 대회 안에서만 복사되므로 모든 주문의 대회가 같다.
        String eventId = members.getFirst().policyEvent().eventId();
        List<RegistrationActionPolicyInput> policies = actionPolicies.loadEnabledRegistrationActionPolicies(eventId,
                List.of(RegistrationActionType.MODIFY, RegistrationActionType.REFUND, RegistrationActionType.PAYMENT));
        List<RegistrationQueryResponse> orders = registrationQueries.assemblePersonalResponses(eventId, members, now, policies);

        List<String> ids = members.stream().map(RegistrationQueryData.Member::id).toList();
        Map<String, List<RegistrationItem>> itemsByRegistration = itemRepository.findAllByRegistrationIds(ids).stream()
                .collect(Collectors.groupingBy(item -> item.getRegistration().getId()));
        Map<String, List<RegistrationEntry>> entriesByItem = entryRepository.findByRegistration_IdIn(ids).stream()
                .collect(Collectors.groupingBy(entry -> entry.getRegistrationItem().getId()));

        return java.util.stream.IntStream.range(0, members.size()).mapToObj(index -> {
            RegistrationQueryData.Member member = members.get(index);
            LocalDateTime registrationDate = member.registrationDate();
            return new ParticipantOrderResponse(
                    orders.get(index),
                    member.deleted(),
                    registrationDate == null ? null
                            : policyEvaluator.deadlineOf(member.policyEvent(), registrationDate, Action.MODIFY, policies),
                    registrationDate == null ? null
                            : policyEvaluator.deadlineOf(member.policyEvent(), registrationDate, Action.REFUND, policies),
                    itemsByRegistration.getOrDefault(member.id(), List.of()).stream()
                            .sorted(Comparator.comparingInt(RegistrationItem::getSortOrder))
                            .map(item -> toItem(item, entriesByItem.getOrDefault(item.getId(), List.of())))
                            .toList());
        }).toList();
    }

    /** 받는 사람은 받는 사람 수정 목록과 같은 순서(종목 순서 → id)로 둔다. */
    private static ParticipantOrderResponse.Item toItem(RegistrationItem item, List<RegistrationEntry> entries) {
        return new ParticipantOrderResponse.Item(
                item.getId(),
                item.getEventCategory().getId(),
                item.getEventCategory().getName(),
                item.getEventCategory().getProductType(),
                item.getAmount(),
                item.getSouvenirJson() == null ? List.of() : item.getSouvenirJson(),
                entries.stream()
                        .sorted(Comparator.comparing((RegistrationEntry entry) -> entry.getEventCategory().getOrder(),
                                        Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(RegistrationEntry::getId))
                        .map(entry -> new ParticipantOrderResponse.Entry(
                                entry.getId(),
                                entry.getEventCategory().getId(),
                                entry.getEventCategory().getName(),
                                entry.getName(),
                                entry.getPhNum(),
                                entry.getBirth(),
                                entry.getGender(),
                                entry.getBibNumber()))
                        .toList());
    }
}
