package kr.co.teambrain.marvelrun.admin.event.policy;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyModels.Action;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyModels.RegistrationActionPolicyInput;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationActionType;

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

    /** 차수를 이루는 정책 행의 작업 종류다. 차수를 읽는 곳은 모두 이 목록으로 정책 행을 읽는다. */
    public static final List<RegistrationActionType> ROUND_ACTION_TYPES =
            List.of(RegistrationActionType.MODIFY, RegistrationActionType.PAYMENT, RegistrationActionType.REFUND);

    /** 차수 번호는 신청 시작 순서(1부터)이며 마감(deadline)은 적용 개시 시각이다. 구간은 [startAt, endAt)이다. */
    public record Round(int number, LocalDateTime startAt, LocalDateTime endAt, LocalDateTime deadline) {
        /** 기준 시각이 마감 시각 이후면 마감된 차수다. */
        public boolean closed(LocalDateTime now) { return !now.isBefore(deadline); }

        /** 신청 시작이 지났고 아직 마감 전이면 진행 중인 차수다. */
        public boolean inProgress(LocalDateTime now) { return !now.isBefore(startAt) && !closed(now); }
    }

    /** 차수 변경 요청의 한 차수이거나 계산된 구간이다. */
    public record Range(LocalDateTime startAt, LocalDateTime deadline) { }

    /** 명단에 담을 신청 시각 범위 [from, to)다. null이면 그쪽 끝을 제한하지 않는다. */
    public record RosterRange(LocalDateTime from, LocalDateTime to) { }

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

    /** 활성 MODIFY·PAYMENT·REFUND 행 가운데 세 작업이 모두 있는 차수 묶음에 들지 않는 행이 있으면 true다. */
    public static boolean hasRowOutsideRounds(List<RegistrationActionPolicyInput> policies) {
        List<Round> rounds = fromPolicies(policies);
        return policies.stream()
                .filter(policy -> policy.enabled() && ROUND_ACTIONS.contains(policy.action()))
                .anyMatch(policy -> rounds.stream().noneMatch(round -> round.startAt().equals(policy.registrationStart())
                        && round.endAt().equals(policy.registrationEnd())
                        && round.deadline().equals(policy.effectiveFrom())));
    }

    /**
     * 차수 명단의 신청 시각 범위다. 앞 차수의 끝부터 이 차수의 끝까지이며 1차는 처음부터, 마지막 차수는 끝까지다.
     * 그래서 차수 사이 빈 기간이나 마지막 마감 뒤에 찍힌 신청(외부 결제 등)도 어느 한 차수 명단에 들어간다.
     */
    public static RosterRange rosterRange(List<Round> rounds, Round round) {
        int index = rounds.indexOf(round);
        if (index < 0) {
            throw new CustomException(ErrorCode.VIRTUAL_ROUND_NOT_FOUND);
        }
        LocalDateTime from = index == 0 ? null : rounds.get(index - 1).endAt();
        LocalDateTime to = index == rounds.size() - 1 ? null : round.endAt();
        return new RosterRange(from, to);
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
     * 진행 중인 기존 차수는 같은 시작 시각으로 요청에 있어야 한다(VIRTUAL_ROUND_STARTED_IMMUTABLE). 마감은 바꿀 수 있다.
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
        // 신청이 들어오기 시작한 차수는 빼거나 시작을 옮길 수 없다. 옮기면 이미 들어온 신청이 차수 밖으로 빠진다.
        for (Round round : existing) {
            if (round.inProgress(now) && sorted.stream().noneMatch(range -> range.startAt().equals(round.startAt()))) {
                throw new CustomException(ErrorCode.VIRTUAL_ROUND_STARTED_IMMUTABLE);
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
