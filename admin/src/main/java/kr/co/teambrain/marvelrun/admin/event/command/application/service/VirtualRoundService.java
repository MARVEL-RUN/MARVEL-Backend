package kr.co.teambrain.marvelrun.admin.event.command.application.service;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationActionPolicy;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.VirtualRoundsRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.VirtualRoundsResponse;
import kr.co.teambrain.marvelrun.admin.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.admin.event.command.repository.RegistrationActionPolicyCommandRepository;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyModels.RegistrationActionPolicyInput;
import kr.co.teambrain.marvelrun.admin.event.policy.VirtualRounds;
import kr.co.teambrain.marvelrun.admin.event.policy.VirtualRounds.Range;
import kr.co.teambrain.marvelrun.admin.event.policy.VirtualRounds.Round;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationActionPolicyReader;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventFormat;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationActionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 버추얼런 차수를 registration_action_policy 행으로 조회하고 교체한다.
 * 마감된 차수의 행은 건드리지 않고, 나머지 차수와 신규 신청 차단(REGIST) 행은 다시 만든다.
 */
@Service
@RequiredArgsConstructor
public class VirtualRoundService {
    private static final List<RegistrationActionType> ALL_ACTIONS =
            List.of(RegistrationActionType.REGIST, RegistrationActionType.MODIFY,
                    RegistrationActionType.PAYMENT, RegistrationActionType.REFUND);

    private final ServerTimeProvider serverTimeProvider;
    private final EventCommandRepository eventRepository;
    private final RegistrationActionPolicyReader policyReader;
    private final RegistrationActionPolicyCommandRepository policyRepository;

    /** 활성 정책에서 읽은 차수 목록을 반환한다. */
    @Transactional(readOnly = true)
    public VirtualRoundsResponse getRounds(String eventId) {
        LocalDateTime now = serverTimeProvider.currentDateTime();
        Event event = eventRepository.findById(eventId).orElseThrow(() -> new CustomException(ErrorCode.EVENT_NOT_FOUND));
        requireVirtual(event);
        return toResponse(loadRounds(eventId), now);
    }

    /** 차수 전체를 요청 값으로 교체한다. 검증에 실패하면 아무것도 바꾸지 않는다. */
    @Transactional
    public VirtualRoundsResponse replaceRounds(String eventId, VirtualRoundsRequest request) {
        LocalDateTime now = serverTimeProvider.currentDateTime();
        Event event = eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new CustomException(ErrorCode.EVENT_NOT_FOUND));
        requireVirtual(event);
        LocalDateTime registDeadline = event.getRegistDeadline();
        if (registDeadline == null) {
            throw new CustomException(ErrorCode.VIRTUAL_ROUND_INVALID);
        }

        // 차수로 묶이지 않는 행이 있으면 어느 행이 마감 차수의 것인지 알 수 없으므로 아무것도 지우지 않는다.
        List<RegistrationActionPolicyInput> policies = loadPolicies(eventId);
        if (VirtualRounds.hasRowOutsideRounds(policies)) {
            throw new CustomException(ErrorCode.VIRTUAL_ROUND_POLICY_INCONSISTENT);
        }
        List<Round> existing = VirtualRounds.fromPolicies(policies);
        List<Range> requested = request.rounds().stream().map(r -> new Range(r.startAt(), r.deadline())).toList();
        List<Range> rounds = VirtualRounds.validateRequest(requested, existing, event.getRegistStartDate(), registDeadline, now);

        // 마감된 차수의 행만 남기고 나머지 정책 행은 지운다.
        List<Round> closedRounds = existing.stream().filter(round -> round.closed(now)).toList();
        List<Range> closed = closedRounds.stream().map(round -> new Range(round.startAt(), round.deadline())).toList();
        List<RegistrationActionPolicy> deleting = policyRepository.findAllByEventIdAndActionTypeIn(eventId, ALL_ACTIONS)
                .stream().filter(row -> !isClosedRoundRow(row, closedRounds)).toList();
        policyRepository.deleteAll(deleting);
        policyRepository.flush();

        List<RegistrationActionPolicy> inserting = new ArrayList<>();
        for (Range range : rounds) {
            if (closed.contains(range)) { continue; }
            for (RegistrationActionType type : VirtualRounds.ROUND_ACTION_TYPES) {
                inserting.add(policy(eventId, type, range.startAt(), range.deadline(), range.deadline()));
            }
        }
        for (Range block : VirtualRounds.registBlocks(rounds, registDeadline)) {
            inserting.add(policy(eventId, RegistrationActionType.REGIST, block.startAt(), block.deadline(), block.startAt()));
        }
        policyRepository.saveAll(inserting);
        policyRepository.flush();

        return toResponse(loadRounds(eventId), now);
    }

    /** 마감된 차수의 활성 MODIFY·PAYMENT·REFUND 행인지 판정한다. */
    private boolean isClosedRoundRow(RegistrationActionPolicy row, List<Round> closedRounds) {
        return row.isEnabled() && VirtualRounds.ROUND_ACTION_TYPES.contains(row.getActionType())
                && closedRounds.stream().anyMatch(round -> round.startAt().equals(row.getRegistrationStartAt())
                        && round.endAt().equals(row.getRegistrationEndAt())
                        && round.deadline().equals(row.getEffectiveFrom()));
    }

    private RegistrationActionPolicy policy(String eventId, RegistrationActionType type,
            LocalDateTime start, LocalDateTime end, LocalDateTime effectiveFrom) {
        return RegistrationActionPolicy.builder().eventId(eventId).actionType(type)
                .registrationStartAt(start).registrationEndAt(end).effectiveFrom(effectiveFrom).enabled(true).build();
    }

    private List<Round> loadRounds(String eventId) {
        return VirtualRounds.fromPolicies(loadPolicies(eventId));
    }

    private List<RegistrationActionPolicyInput> loadPolicies(String eventId) {
        return policyReader.loadEnabledRegistrationActionPolicies(eventId, VirtualRounds.ROUND_ACTION_TYPES);
    }

    private void requireVirtual(Event event) {
        if (event.getEventFormat() != EventFormat.VIRTUAL) {
            throw new CustomException(ErrorCode.VIRTUAL_EVENT_REQUIRED);
        }
    }

    private VirtualRoundsResponse toResponse(List<Round> rounds, LocalDateTime now) {
        return new VirtualRoundsResponse(rounds.stream()
                .map(round -> new VirtualRoundsResponse.Round(round.number(), round.startAt(), round.deadline(), round.closed(now)))
                .toList());
    }
}
