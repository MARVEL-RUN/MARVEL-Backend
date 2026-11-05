package kr.co.teambrain.marvelrun.admin.event.command.application.service;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.command.application.context.RegistrationPolicyContext;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.RegistrationEntryModifyRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.RegistrationEntryModifyResponse;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.RegistrationPolicyValidator;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.dto.RegistrationPolicyInput;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.admin.event.command.repository.RegistrationCommandRepository;
import kr.co.teambrain.marvelrun.admin.event.command.repository.RegistrationEntryCommandRepository;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 관리자가 패키지 받는 사람(registration_entry)의 이름·연락처·생년월일·성별을 고친다.
 * 차수 마감 뒤에도 고칠 수 있으므로 기간 정책은 보지 않고, 배번은 건드리지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RegistrationEntryCommandService {

    private final RegistrationCommandRepository registrationCommandRepository;
    private final RegistrationEntryCommandRepository entryRepository;
    private final RegistrationPolicyLoader policyLoader;
    private final RegistrationPolicyValidator policyValidator;
    private final ServerTimeProvider serverTimeProvider;

    /** 요청에 담긴 받는 사람만 정책 검증을 거쳐 고친다. 한 명이라도 거절되면 전체를 취소한다. */
    public RegistrationEntryModifyResponse modifyEntries(String registrationId, RegistrationEntryModifyRequest request) {
        LocalDateTime now = serverTimeProvider.currentDateTime();
        Registration registration = registrationCommandRepository.findById(registrationId)
                .orElseThrow(() -> new CustomException(ErrorCode.REGISTRATION_NOT_FOUND));
        if (registration.isSoftDeleted()) {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET);
        }
        if (registration.getEventCategory().getProductType() != ProductType.PACKAGE) {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_ENTRIES,
                    " 패키지 신청만 받는 사람을 수정할 수 있습니다. 단품은 신청자 정보를 따르므로 기본정보를 수정해주세요.");
        }

        Set<String> requestedIds = new HashSet<>();
        for (RegistrationEntryModifyRequest.Entry item : request.entries()) {
            if (!requestedIds.add(item.entryId())) {
                throw new CustomException(ErrorCode.INVALID_REGISTRATION_ENTRIES,
                        " 같은 참가 항목이 중복되어 있습니다.");
            }
        }
        Map<String, RegistrationEntry> owned = entryRepository.findByRegistration_Id(registrationId).stream()
                .collect(Collectors.toMap(RegistrationEntry::getId, Function.identity()));
        if (!owned.keySet().containsAll(requestedIds)) {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_ENTRIES,
                    " 이 신청의 참가 항목이 아닙니다.");
        }

        // 사용자 서버와 같은 규칙: 보호자 정보는 신청에 저장된 값을 쓰고, 받는 사람 생년월일로 종목 정책을 본다.
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
                    new RegistrationPolicyInput(item.birth(), registration.getGuardianName(), registration.isGuardianConsent()),
                    applicationDate);
            policyValidator.validateCategoryBirth(category, policies.categoryPolicies().get(category.getId()), birth);
        }

        List<RegistrationEntry> updated = request.entries().stream().map(item -> {
            RegistrationEntry entry = owned.get(item.entryId());
            entry.modifyRecipientByAdmin(item.name(), item.phNum(), item.birth(), item.gender());
            return entry;
        }).toList();
        registration.recordRecipientModifiedByAdmin(now, updated.size());
        return new RegistrationEntryModifyResponse(updated.stream().map(RegistrationEntryModifyResponse.Entry::from).toList());
    }
}
