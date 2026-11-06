package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.user.participantauth.command.application.service.ParticipantOrderAccess;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationActionType;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationPolicyContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationEntryModifyRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.RegistrationEntryListResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.RegistrationEntryModifyResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationInformationPolicyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationPolicyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.dto.RegistrationPolicyInput;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationEntryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyModels.Action;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 로그인한 참가자가 자기 상품 신청의 받는 사람(참가 항목) 이름·연락처·생년월일·성별을 고친다.
 *
 * 단품도 신청자 값을 따르지 않으므로 신청자 정보 수정과 따로 둔다.
 * 개인정보 수정과 같은 상태·기간(MODIFY) 정책을 따르고, 차수 마감 뒤에는 관리자만 고친다.
 * 가격·정원·결제·배번은 건드리지 않는다. 상품은 생년월일로 가격·정원을 정하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RegistrationEntryModificationService {

    private final ServerTimeProvider time;
    private final EntityManager entityManager;
    private final RegistrationEntryCommandRepository entryRepository;
    private final RegistrationInformationPolicyValidator informationPolicyValidator;
    private final RegistrationActionPolicyService actionPolicies;
    private final RegistrationPolicyLoader policyLoader;
    private final RegistrationPolicyValidator policyValidator;

    /** 받는 사람 목록. 수정할 entryId를 여기서 읽는다. 일반 종목 신청은 빈 목록이다. */
    @Transactional(readOnly = true)
    public RegistrationEntryListResponse findEntries(String registrationId, String ordererKey) {
        Registration registration = entityManager.find(Registration.class, registrationId);
        if (registration == null) {
            throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED);
        }
        ParticipantOrderAccess.verifyOwned(registration, ordererKey);
        if (registration.isSoftDeleted()) {
            throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED);
        }
        return new RegistrationEntryListResponse(entryRepository.findByRegistration_Id(registrationId).stream()
                .sorted(Comparator.comparingInt((RegistrationEntry entry) -> entry.getRegistrationItem().getSortOrder())
                        .thenComparing(entry -> entry.getEventCategory().getOrder(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(RegistrationEntry::getId))
                .map(RegistrationEntryListResponse.Entry::from)
                .toList());
    }

    /** 요청에 담긴 받는 사람만 정책 검증을 거쳐 고친다. 한 명이라도 거절되면 전체를 취소한다. */
    @Transactional
    public RegistrationEntryModifyResponse modifyEntries(String registrationId, String ordererKey,
                                                         RegistrationEntryModifyRequest request) {
        LocalDateTime now = time.currentDateTime();
        Registration registration = lock(registrationId, ordererKey);

        informationPolicyValidator.validateStatus(registration);
        if (!registration.isProductPurchase()) {
            throw new CustomException(ErrorCode.REGISTRATION_ENTRY_PRODUCT_ONLY, " registrationId=" + registrationId);
        }
        // 신청자 개인정보 수정과 같은 기간 규칙: 대회 수정 기간과 신청일이 속한 차수의 MODIFY 정책
        informationPolicyValidator.validateRegistrationModificationPeriod(registration.getEvent(), now);
        actionPolicies.validateRegistrationActionPolicy(registration.getEvent(), registration, Action.MODIFY, now,
                actionPolicies.loadEnabledRegistrationActionPolicies(registration.getEvent().getId(),
                        RegistrationActionType.MODIFY));

        Set<String> requestedIds = new HashSet<>();
        for (RegistrationEntryModifyRequest.Entry item : request.entries()) {
            if (!requestedIds.add(item.entryId())) {
                throw new CustomException(ErrorCode.REGISTRATION_ENTRY_DUPLICATED, " entryId=" + item.entryId());
            }
        }
        Map<String, RegistrationEntry> owned = entryRepository.findByRegistration_Id(registrationId).stream()
                .collect(Collectors.toMap(RegistrationEntry::getId, Function.identity()));
        if (!owned.keySet().containsAll(requestedIds)) {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_ENTRIES, " 다른 신청의 참가 항목입니다.");
        }

        // 신청 때와 같은 규칙: 보호자 정보는 신청에 저장된 값을 쓰고, 받는 사람 생년월일로 자기 종목 정책을 본다.
        Set<String> categoryIds = requestedIds.stream()
                .map(id -> owned.get(id).getEventCategory().getId())
                .collect(Collectors.toSet());
        RegistrationPolicyContext policies = policyLoader.load(registration.getEvent().getId(), categoryIds, Set.of());
        LocalDate applicationDate = now.toLocalDate();
        for (RegistrationEntryModifyRequest.Entry item : request.entries()) {
            EventCategory category = owned.get(item.entryId()).getEventCategory();
            LocalDate birth = policyValidator.validateParticipant(
                    registration.getEvent(),
                    policies.eventPolicy(),
                    new RegistrationPolicyInput(item.birth(), registration.getGuardianName(),
                            registration.isGuardianConsent()),
                    applicationDate);
            policyValidator.validateCategoryBirth(category, policies.categoryPolicies().get(category.getId()), birth);
        }

        return new RegistrationEntryModifyResponse(request.entries().stream().map(item -> {
            RegistrationEntry entry = owned.get(item.entryId());
            entry.updateRecipient(item.name(), item.phNum(), item.birth(), item.gender());
            return RegistrationEntryModifyResponse.Entry.from(entry);
        }).toList());
    }

    /**
     * 신청을 잠그고 토큰 주문자의 살아 있는 온라인 개인 주문인지 본다.
     * 정원·결제를 건드리지 않으므로 대회·결제 행은 잠그지 않는다. 다른 수정과는 신청 행 잠금으로 순서를 맞춘다.
     */
    private Registration lock(String registrationId, String ordererKey) {
        Registration registration = entityManager.find(Registration.class, registrationId,
                LockModeType.PESSIMISTIC_WRITE);
        if (registration == null) {
            throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED);
        }
        ParticipantOrderAccess.verifyOwned(registration, ordererKey);
        if (registration.isSoftDeleted()) {
            throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED);
        }
        registration.validateOnlineRegistrationProcessingAllowed();
        return registration;
    }
}
