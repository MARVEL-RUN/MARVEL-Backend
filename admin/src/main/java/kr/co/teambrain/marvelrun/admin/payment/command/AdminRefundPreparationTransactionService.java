package kr.co.teambrain.marvelrun.admin.payment.command;

import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import kr.co.teambrain.marvelrun.admin.common.exception.*;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.*;
import kr.co.teambrain.marvelrun.admin.user.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.admin.event.command.application.service.RegistrationPricingService;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.RegistrationPolicyCandidateValidator;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.dto.*;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.dto.CapacityRequirementInput;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.service.*;
import kr.co.teambrain.marvelrun.admin.payment.command.dto.AdminPaymentPartialRefundTarget;
import kr.co.teambrain.marvelrun.admin.payment.command.application.creator.*;
import kr.co.teambrain.marvelrun.admin.payment.command.application.dto.*;
import kr.co.teambrain.marvelrun.admin.payment.command.application.domain.PaymentProcessLog;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.*;

/** 정책·정원·계약·원귀속·환불 시도·로그를 한 범위의 트랜잭션에 저장한다. PG는 호출하지 않는다. */
@Service
@RequiredArgsConstructor
public class AdminRefundPreparationTransactionService {
    private final AdminRefundAccessService access;
    private final AdminRefundPreparationStore store;
    private final RegistrationPolicyCandidateValidator policies;
    private final RegistrationPricingService pricing;
    private final CapacityRequirementResolver requirements;
    private final ReservationCapacityDiffService diffs;
    private final CapacityModificationService movement;
    private final ReservationRemovalService removal;
    private final ModificationRefundPlanner planner;
    private final PaymentCancelAllocationCreator allocationCreator;
    private final AdminRefundTime time;
    private final Validator validator;

    /** 결제액 환불은 참가 취소를 동반한다. 단체 일부도 선택한 인원만 취소한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AdminRefundPrepared prepareFull(String eventId, String organizationId, List<String> registrationIds,
            AdminRefundCommandContext command) {
        return prepare(eventId, organizationId, registrationIds, null, command);
    }

    /** 관리자 정보 변경의 환불·추가 납부·동일 금액을 서버 가격으로 계산한다. 추가 주문은 만들지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AdminRefundPrepared preparePartial(String eventId, String organizationId,
            List<AdminPaymentPartialRefundTarget> targets, AdminRefundCommandContext command) {
        if (targets == null || targets.isEmpty() || targets.size() > AdminRefundAccessService.MAX_TARGETS) { throw invalid(); }
        for (var target : targets) {
            if (target == null || !validator.validate(target).isEmpty()) { throw invalid(); }
        }
        return prepare(eventId, organizationId, targets.stream().map(AdminPaymentPartialRefundTarget::registrationId).toList(),
                targets, command);
    }

    /** 실제 계산 전 전체 접근을 검증하고, 실제 변경 전 전체 원귀속을 대사한다. */
    private AdminRefundPrepared prepare(String eventId, String organizationId, List<String> ids,
            List<AdminPaymentPartialRefundTarget> targets, AdminRefundCommandContext command) {
        Objects.requireNonNull(command, "관리자 추적정보");
        AdminRefundLockedScope scope = targets == null ? access.lock(eventId, organizationId, ids)
                : access.lockAdjustment(eventId, organizationId, ids);
        LocalDateTime now = time.now();
        Event event = store.current(Event.class, eventId);
        Organization organization = organizationId == null ? null : store.current(Organization.class, organizationId);
        List<Registration> registrations = scope.registrations().stream()
                .map(row -> store.current(Registration.class, row.id())).toList();
        if (command.expectedRegistrationVersion() != null
                && (registrations.size() != 1 || !Objects.equals(registrations.getFirst().getVersion(), command.expectedRegistrationVersion()))) {
            throw new CustomException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        List<Reservation> reservations = store.reservations(registrations.stream().map(Registration::getId).toList());
        Map<String, Reservation> byRegistration = new TreeMap<>();
        for (Reservation reservation : reservations) {
            String id = reservation.getRegistration().getId();
            if (reservation.getStatus() != ReservationStatus.CONSUMED
                    || byRegistration.put(id, reservation) != null) { throw invalid(); }
        }
        if (!byRegistration.keySet().equals(new TreeSet<>(ids))) { throw invalid(); }
        Map<String, AdminPaymentPartialRefundTarget> requests = new HashMap<>();
        if (targets != null) { targets.forEach(t -> requests.put(t.registrationId(), t)); }
        Map<String, RegistrationPolicyCandidateResult> checkedById = new HashMap<>();
        if (targets != null) {
            if (event.getStartDate() == null) { throw invalid(); }
            List<RegistrationPolicyCandidateRequest> policyInputs = new ArrayList<>();
            for (Registration registration : registrations) {
                AdminPaymentPartialRefundTarget target = requests.get(registration.getId());
                if (target == null) { throw invalid(); }
                AdminAdjustmentTemporaryBlock.validateBirthTransition(
                        registration.getBirth(), target.birth() == null ? registration.getBirth() : target.birth(),
                        event.getStartDate().toLocalDate());
                policyInputs.add(new RegistrationPolicyCandidateRequest(target.eventCategoryId(), target.selectedSouvenirList(),
                        new RegistrationPolicyInput(target.birth() == null ? registration.getBirth() : target.birth(),
                        organization == null ? registration.getGuardianName() : organization.getLeaderName(),
                        organization == null ? registration.isGuardianConsent() : organization.isGuardianConsent())));
            }
            // 관리자 전용 후보 검증은 참가 정책을 제외하고 소속·실제 선택값·날짜 형식을 검증한다.
            List<RegistrationPolicyCandidateResult> checked = policies.validateAdminAdjustment(event, policyInputs, now);
            if (checked.size() != registrations.size()) { throw invalid(); }
            for (int i = 0; i < registrations.size(); i++) { checkedById.put(registrations.get(i).getId(), checked.get(i)); }
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Registration registration : registrations) {
            // 소속·금액은 JDBC 검사 후 현재 Entity에서도 대조한다.
            if (!eventId.equals(registration.getEvent().getId())
                    || !Objects.equals(organizationId, registration.getOrganization() == null ? null : registration.getOrganization().getId())
                    || registration.isSoftDeleted() || registration.getPaidAmount() == null || registration.getContractAmount() == null
                    || registration.getPaidAmount().signum() < 0 || registration.getContractAmount().signum() < 0
                    || (registration.getStatus() == RegistrationStatus.CONFIRMED
                        ? registration.getPaidAmount().compareTo(registration.getContractAmount()) != 0
                        : targets == null || registration.getStatus() != RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED
                          || registration.getContractAmount().compareTo(registration.getPaidAmount()) <= 0)) { throw invalid(); }
            if (targets == null) {
                candidates.add(new Candidate(registration, registration.getEventCategory(), registration.getSouvenirJson(), registration.getBirth(), BigDecimal.ZERO, true));
            } else {
                AdminPaymentPartialRefundTarget target = requests.get(registration.getId());
                if (target == null) { throw invalid(); }
                RegistrationPolicyCandidateResult checked = checkedById.get(registration.getId());
                BigDecimal amount = pricing.calculateContractAmount(event, checked.eventCategory(), checked.birth().toString());
                if (amount == null || amount.signum() < 0) { throw invalid(); }
                AdminAdjustmentTemporaryBlock.validateCategoryPriceIncrease(
                        registration.getEventCategory().getId(), checked.eventCategory().getId(),
                        registration.getContractAmount(), amount);
                candidates.add(new Candidate(registration, checked.eventCategory(), checked.souvenirJsons(), checked.birth().toString(), amount,
                        amount.signum() == 0 && Boolean.FALSE.equals(target.keepParticipationWhenZero())));
            }
        }
        // 비영속 후보로 원귀속을 먼저 검증하여 실제 변경 전 불일치를 차단한다.
        List<Registration> projected = candidates.stream().<Registration>map(c -> Registration.builder()
                .id(c.registration().getId()).contractAmount(c.amount()).paidAmount(c.registration().getPaidAmount())
                .softDeleted(c.cancel()).status(c.cancel() ? RegistrationStatus.CANCELLATION_PENDING : RegistrationStatus.PARTIAL_REFUND_REQUIRED)
                .build()).toList();
        List<RefundPaymentLedger> ledgers = store.ledgers(scope);
        for (RefundPaymentLedger ledger : ledgers) {
            for (kr.co.teambrain.marvelrun.admin.payment.command.application.domain.PaymentAllocation allocation : ledger.allocations()) {
                Registration owner = allocation.getRegistration();
                if (owner == null || owner.getEvent() == null || !eventId.equals(owner.getEvent().getId())
                        || !Objects.equals(organizationId, owner.getOrganization() == null ? null : owner.getOrganization().getId())) {
                    throw invalid();
                }
            }
        }
        List<Payment> readyToInvalidate = new ArrayList<>();
        Set<String> selectedIds = new HashSet<>(ids);
        for (RefundPaymentLedger ledger : ledgers) {
            if (ledger.payment().getProcessStatus()
                    != kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus.READY) { continue; }
            boolean selected = ledger.allocations().stream().anyMatch(a -> selectedIds.contains(a.getRegistration().getId()))
                    || (ledger.payment().getRegistration() != null && selectedIds.contains(ledger.payment().getRegistration().getId()));
            if (!selected) { continue; }
            // 일부 인원 환불로 다른 인원의 미결제 주문까지 무효화하지 않는다.
            if (ledger.allocations().stream().anyMatch(a -> !selectedIds.contains(a.getRegistration().getId()))) {
                throw new CustomException(ErrorCode.REGISTRATION_MODIFICATION_PAYMENT_CONFLICT);
            }
            readyToInvalidate.add(ledger.payment());
        }
        List<RefundPreparationPlan> plans = planner.plan(projected, ledgers);
        if (targets == null && plans.isEmpty()) { throw invalid(); }
        // 관리자 배치 단일 대상의 외부 호출 수도 제한한다. 준비 변경 전이므로 전체 롤백된다.
        if (command.batchId() != null && plans.size() > 10) { throw invalid(); }

        List<Candidate> retained = candidates.stream().filter(c -> !c.cancel()).toList();
        if (!retained.isEmpty()) {
            List<CapacityRequirementInput> inputs = retained.stream().map(c -> CapacityRequirementInput.fromCandidate(c.category().getId(),
                    c.birth(), event.getStartDate().toLocalDate(), c.souvenirs())).toList();
            List<Map<String, Integer>> needed = requirements.resolveAll(eventId, inputs);
            Map<String, Map<String, Integer>> newRequirements = new TreeMap<>();
            for (int i = 0; i < retained.size(); i++) {
                newRequirements.put(byRegistration.get(retained.get(i).registration().getId()).getId(), needed.get(i));
            }
            List<Reservation> selectedReservations = retained.stream().map(c -> byRegistration.get(c.registration().getId())).toList();
            movement.moveAllForAdminAdjustment(eventId, diffs.compareAll(selectedReservations, newRequirements), now);
        }
        List<String> removed = candidates.stream().filter(Candidate::cancel).map(c -> c.registration().getId()).sorted().toList();
        removal.releaseAll(eventId, removed, now);
        for (Payment payment : readyToInvalidate) { payment.invalidateForRegistrationModification(); }
        List<AdminRefundPrepared.Member> members = new ArrayList<>();
        for (Candidate candidate : candidates) {
            Registration registration = candidate.registration();
            BigDecimal previous = registration.getContractAmount();
            String previousBirth = registration.getBirth();
            String previousCategory = registration.getEventCategory().getId();
            if (targets != null) {
                registration.applyAdminAdjustmentCandidate(candidate.category(), candidate.souvenirs(), candidate.birth(), candidate.amount());
            }
            if (candidate.cancel()) { registration.cancelParticipation(); }
            Reservation reservation = byRegistration.get(registration.getId());
            registration.reconcileModificationFinancialState(reservation.getStatus());
            members.add(new AdminRefundPrepared.Member(registration.getId(), previous, registration.getContractAmount(),
                    registration.getPaidAmount(), registration.getStatus(), reservation.getStatus(), registration.isSoftDeleted(),
                    previousBirth, registration.getBirth(), previousCategory, registration.getEventCategory().getId()));
        }
        String correlationId = UUID.randomUUID().toString();
        List<AdminRefundPrepared.Refund> refunds = new ArrayList<>();
        for (RefundPreparationPlan plan : plans) {
            boolean cancellation = plan.targets().stream().allMatch(t -> t.originalAllocation().getRegistration().isSoftDeleted());
            PaymentCancel prepared = cancellation
                    ? PaymentCancel.prepareRegistrationCancellation(plan.payment(), plan.amount(), plan.type(), "refund-" + UUID.randomUUID())
                    : PaymentCancel.preparePriceAdjustment(plan.payment(), plan.amount(), plan.type(), "refund-" + UUID.randomUUID());
            prepared.recordAdminReason(command.reason());
            PaymentCancel saved = store.save(prepared);
            allocationCreator.create(saved, plan.targets());
            Map<String, Object> metadata = new LinkedHashMap<>();
            // 환불 준비와 동일 트랜잭션에 저장: preparation_json 저장 전 종료에도 기존 시도 추적 가능.
            metadata.put("batchId", command.batchId()); metadata.put("batchItemNo", command.batchItemNo());
            metadata.put("requestId", command.requestId()); metadata.put("adminId", command.adminId());
            metadata.put("reason", command.reason()); metadata.put("preparedAt", now.toString());
            metadata.put("amount", plan.amount()); metadata.put("allocationCount", plan.targets().size());
            metadata.put("operation", targets == null ? "PAYMENT_REFUND" : "PAYMENT_PARTIAL_REFUND");
            metadata.put("participationCanceledIds", removed);
            if (targets != null) { metadata.put("requestedChanges", targets); }
            store.log(PaymentProcessLog.builder().paymentId(plan.payment().getId()).paymentCancelId(saved.getId())
                    .orderId(plan.payment().getOrderId()).correlationId(correlationId).idempotencyKey(saved.getIdempotencyKey())
                    .processType(PaymentProcessType.CANCEL_PREPARED).source(PaymentProcessSource.ADMIN).metadata(metadata).build());
            refunds.add(new AdminRefundPrepared.Refund(saved.getId(), plan.payment().getId(), plan.amount(), saved.getCancelType(), saved.getStatus()));
        }
        store.flush();
        AdminRefundPrepared prepared = new AdminRefundPrepared(command.requestId(), correlationId, eventId, organizationId, now, members, refunds);
        // 추가 결제/동일 금액 변경은 금융 시도가 없으므로 업무 변경과 같은 Tx에 배치 증거를 남긴다.
        if (targets != null) { store.recordAdjustmentPrepared(command, prepared); }
        return prepared;
    }

    /** 정책 계산 결과와 참가 취소 분기를 분리한다. 결제 롤백 기능은 여기 포함하지 않는다. */
    private record Candidate(Registration registration, EventCategory category,
            List<kr.co.teambrain.marvelrun.common.json_object.SouvenirJson> souvenirs, String birth, BigDecimal amount, boolean cancel) { }
    private static CustomException invalid() { return new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR); }
}
