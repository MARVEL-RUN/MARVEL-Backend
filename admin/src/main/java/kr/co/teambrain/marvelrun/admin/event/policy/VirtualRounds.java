package kr.co.teambrain.marvelrun.admin.event.policy;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyModels.Action;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyModels.RegistrationActionPolicyInput;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 버추얼런 차수(round)를 정책 행에서 읽고 차수 변경 요청을 검증하는 순수 로직이다.
 *
 * 한 차수는 같은 [신청 시작, 신청 끝)과 적용 개시(effectiveFrom = 마감)를 가진 MODIFY·PAYMENT·REFUND 행 묶음이다.
 * REGIST 행은 차수 사이의 신규 신청 차단 구간이며 차수로 세지 않는다.
 */
public final class VirtualRounds {
    /** 정적 메서드 이름 공간이며 인스턴스를 생성하지 않는다. */
    private VirtualRounds() { }

    private static final Set<Action> ROUND_ACTIONS = EnumSet.of(Action.MODIFY, Action.PAYMENT, Action.REFUND);

    /** 차수 번호는 신청 시작 순서(1부터)이며 마감(deadline)은 적용 개시 시각이다. 구간은 [startAt, endAt)이다. */
    public record Round(int number, LocalDateTime startAt, LocalDateTime endAt, LocalDateTime deadline) {
        /** 기준 시각이 마감 시각 이후면 마감된 차수다. */
        public boolean closed(LocalDateTime now) { return !now.isBefore(deadline); }
    }

    /** 차수 변경 요청의 한 차수이거나 계산된 구간이다. */
    public record Range(LocalDateTime startAt, LocalDateTime deadline) { }

    /** 활성 MODIFY·PAYMENT·REFUND 행을 구간별로 묶어 세 작업이 모두 있는 묶음만 차수로 만든다. */
    public static List<Round> fromPolicies(List<RegistrationActionPolicyInput> policies) {
        Map<List<LocalDateTime>, Set<Action>> groups = new LinkedHashMap<>();
        for (RegistrationActionPolicyInput policy : policies) {
            if (!policy.enabled() || !ROUND_ACTIONS.contains(policy.action())) { continue; }
            groups.computeIfAbsent(List.of(policy.registrationStart(), policy.registrationEnd(), policy.effectiveFrom()),
                    key -> EnumSet.noneOf(Action.class)).add(policy.action());
        }
        List<List<LocalDateTime>> keys = groups.entrySet().stream()
                .filter(entry -> entry.getValue().containsAll(ROUND_ACTIONS))
                .map(Map.Entry::getKey)
                .sorted(Comparator.<List<LocalDateTime>, LocalDateTime>comparing(key -> key.get(0))
                        .thenComparing(key -> key.get(1)).thenComparing(key -> key.get(2)))
                .toList();
        List<Round> rounds = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            List<LocalDateTime> key = keys.get(i);
            rounds.add(new Round(i + 1, key.get(0), key.get(1), key.get(2)));
        }
        return rounds;
    }

    /** 마감 시각이 같은 차수가 정확히 하나일 때만 돌려주고 아니면 VIRTUAL_ROUND_NOT_FOUND다. */
    public static Round requireByDeadline(List<Round> rounds, LocalDateTime deadline) {
        List<Round> matched = rounds.stream().filter(round -> round.deadline().equals(deadline)).toList();
        if (matched.size() != 1) {
            throw new CustomException(ErrorCode.VIRTUAL_ROUND_NOT_FOUND);
        }
        return matched.getFirst();
    }

    /**
     * 요청 차수를 시작 순으로 정렬해 검증한 목록을 돌려준다.
     * 시작이 마감보다 앞서야 하고, 차수끼리 겹치지 않으며, 첫 시작은 접수 시작 이전이고 모든 마감은 접수 마감 이내여야 한다.
     * 이미 마감된 기존 차수는 같은 값으로 요청에 있어야 하고(VIRTUAL_ROUND_CLOSED_IMMUTABLE),
     * 새로 만들거나 바꾼 차수의 마감이 현재 이전이어도 VIRTUAL_ROUND_CLOSED_IMMUTABLE이다.
     */
    public static List<Range> validateRequest(List<Range> requested, List<Round> existing,
            LocalDateTime registStartDate, LocalDateTime registDeadline, LocalDateTime now) {
        if (requested == null || requested.isEmpty()) {
            throw new CustomException(ErrorCode.VIRTUAL_ROUND_INVALID);
        }
        List<Range> sorted = requested.stream().sorted(Comparator.comparing(Range::startAt).thenComparing(Range::deadline)).toList();
        // 마감된 기존 차수는 그대로 있어야 한다.
        for (Round round : existing) {
            if (round.closed(now) && !sorted.contains(new Range(round.startAt(), round.deadline()))) {
                throw new CustomException(ErrorCode.VIRTUAL_ROUND_CLOSED_IMMUTABLE);
            }
        }
        for (int i = 0; i < sorted.size(); i++) {
            Range range = sorted.get(i);
            boolean broken = !range.startAt().isBefore(range.deadline())
                    || range.deadline().isAfter(registDeadline)
                    || (i > 0 && range.startAt().isBefore(sorted.get(i - 1).deadline()));
            if (broken) { throw new CustomException(ErrorCode.VIRTUAL_ROUND_INVALID); }
        }
        if (registStartDate != null && sorted.getFirst().startAt().isAfter(registStartDate)) {
            throw new CustomException(ErrorCode.VIRTUAL_ROUND_INVALID);
        }

        // 마감된 시각으로는 새 차수를 만들 수 없다.
        for (Range range : sorted) {
            boolean unchangedClosed = existing.stream().anyMatch(round -> round.closed(now)
                    && round.startAt().equals(range.startAt()) && round.deadline().equals(range.deadline()));
            if (!range.deadline().isAfter(now) && !unchangedClosed) {
                throw new CustomException(ErrorCode.VIRTUAL_ROUND_CLOSED_IMMUTABLE);
            }
        }
        return sorted;
    }

    /** 정렬된 차수 사이의 빈 구간과 마지막 차수 뒤의 구간이 신규 신청 차단(REGIST) 구간이다. */
    public static List<Range> registBlocks(List<Range> sortedRounds, LocalDateTime registDeadline) {
        List<Range> blocks = new ArrayList<>();
        for (int i = 1; i < sortedRounds.size(); i++) {
            Range prev = sortedRounds.get(i - 1);
            Range next = sortedRounds.get(i);
            if (prev.deadline().isBefore(next.startAt())) {
                blocks.add(new Range(prev.deadline(), next.startAt()));
            }
        }
        Range last = sortedRounds.getLast();
        if (last.deadline().isBefore(registDeadline)) {
            blocks.add(new Range(last.deadline(), registDeadline));
        }
        return blocks;
    }
}
