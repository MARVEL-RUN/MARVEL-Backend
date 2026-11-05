package kr.co.teambrain.marvelrun.admin.event.command.application.service;

import kr.co.teambrain.marvelrun.admin.capacity.command.application.service.RegistrationCapacityService;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.BibIssueRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.BibIssueResponse;
import kr.co.teambrain.marvelrun.admin.event.command.repository.BibIssueRepository;
import kr.co.teambrain.marvelrun.admin.event.command.repository.BibIssueRepository.Assignment;
import kr.co.teambrain.marvelrun.admin.event.command.repository.BibIssueRepository.Sequence;
import kr.co.teambrain.marvelrun.admin.event.command.repository.BibIssueRepository.Target;
import kr.co.teambrain.marvelrun.admin.event.policy.VirtualRounds;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationActionPolicyReader;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventFormat;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationActionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 마감된 차수의 확정 신청 참가 항목에 배번을 발급한다.
 * 신청일시 순으로 종목별 순번(bib_sequence)에서 번호를 주며, 한 건이라도 실패하면 전체를 취소한다.
 */
@Service
@RequiredArgsConstructor
public class BibIssueService {
    private final ServerTimeProvider serverTimeProvider;
    private final RegistrationCapacityService capacityService;
    private final RegistrationActionPolicyReader policyReader;
    private final BibIssueRepository repository;

    @Transactional
    public BibIssueResponse issue(String eventId, BibIssueRequest request) {
        LocalDateTime now = serverTimeProvider.currentDateTime();
        LocalDateTime deadline = request.deadline();
        if (now.isBefore(deadline)) {
            throw new CustomException(ErrorCode.BIB_ISSUE_ROUND_NOT_CLOSED);
        }

        Event event = capacityService.lockRegistrationEvent(eventId);
        if (event.getEventFormat() != EventFormat.VIRTUAL) {
            throw new CustomException(ErrorCode.VIRTUAL_EVENT_REQUIRED);
        }
        VirtualRounds.requireByDeadline(VirtualRounds.fromPolicies(policyReader.loadEnabledRegistrationActionPolicies(eventId,
                List.of(RegistrationActionType.MODIFY, RegistrationActionType.PAYMENT, RegistrationActionType.REFUND))), deadline);

        if (repository.countPendingUpgrades(eventId, deadline) > 0) {
            throw new CustomException(ErrorCode.BIB_ISSUE_UPGRADE_PENDING);
        }

        Map<String, Sequence> sequences = repository.lockHomepageSequences(eventId);
        List<Target> targets = repository.findTargets(eventId, deadline);
        for (Target target : targets) {
            if (!sequences.containsKey(target.categoryId())) {
                throw new CustomException(ErrorCode.BIB_SEQUENCE_NOT_CONFIGURED);
            }
        }

        // 신청 순서대로 종목별 다음 번호를 준다.
        Map<String, Integer> lastNumbers = new LinkedHashMap<>();
        Map<String, Integer> firstNumbers = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Target> categories = new LinkedHashMap<>();
        List<Assignment> assignments = new ArrayList<>(targets.size());
        for (Target target : targets) {
            String categoryId = target.categoryId();
            int number = lastNumbers.containsKey(categoryId) ? lastNumbers.get(categoryId) + 1 : sequences.get(categoryId).next();
            lastNumbers.put(categoryId, number);
            firstNumbers.putIfAbsent(categoryId, number);
            counts.merge(categoryId, 1, Integer::sum);
            categories.putIfAbsent(categoryId, target);
            assignments.add(new Assignment(target.entryId(), String.valueOf(number)));
        }

        repository.updateBibs(assignments, now);
        if (!lastNumbers.isEmpty()) {
            repository.updateLastNumbers(eventId, lastNumbers);
        }

        List<BibIssueResponse.PerCategory> perCategory = categories.values().stream()
                .sorted(Comparator.comparingLong(Target::sortOrder))
                .map(target -> new BibIssueResponse.PerCategory(target.categoryId(), target.categoryName(),
                        counts.get(target.categoryId()), firstNumbers.get(target.categoryId()), lastNumbers.get(target.categoryId())))
                .toList();
        return new BibIssueResponse(assignments.size(),
                repository.countAlreadyIssued(eventId, deadline) - assignments.size(),
                repository.countExternalPaymentSkipped(eventId, deadline),
                repository.countRegistrationsWithoutEntries(eventId, deadline),
                perCategory);
    }
}
