package kr.co.teambrain.marvelrun.user.event.command.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationStartGroupModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationStartGroupModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationAccessVerifier;
import kr.co.teambrain.marvelrun.user.event.command.repository.OrganizationCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 본인확인과 소속·그룹 검증으로만 전용 수정을 처리하며 신청 정책·결제 서비스에 의존하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional
public class RegistrationStartGroupModificationService {
    private final RegistrationCommandRepository registrations;
    private final OrganizationCommandRepository organizations;
    private final RegistrationStartGroupResolver startGroups;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager entityManager;

    /** 개인 비밀번호로 인증한 신청의 그룹만 저장한다. 외부결제·접수/수정 기한은 검사하지 않는다. */
    public void modifyPersonalRegistrationStartGroup(String eventId, String registrationId,
            RegistrationStartGroupModificationRequest request) {
        // 개인 대상의 대회 귀속과 비밀번호를 확인한다.
        Registration registration = registrations.findActivePersonalModificationTarget(eventId, registrationId)
                .orElseThrow(() -> new CustomException(ErrorCode.REGISTRATION_NOT_FOUND));
        RegistrationAccessVerifier.verifyPersonalPassword(registration, request.password(), passwordEncoder);
        validateRegistrationTarget(registration);

        // 검증된 그룹을 반영하며 기존 Registration version으로 동시 수정을 보호한다.
        RegistrationStartGroupResolver.Selection selection = startGroups.resolveRegistrationStartGroups(List.of(
                new RegistrationStartGroupResolver.Input(registration.getEventCategory().getId(),
                        request.startGroupId(), request.startGroupUnselected()))).getFirst();
        applyRegistrationStartGroup(registration, selection);
        registrations.flush();
    }

    /** 단체 인증 후 요청에 포함된 인원만 일괄 수정하며 한 명이라도 잘못되면 전부 롤백한다. */
    public void modifyOrganizationRegistrationStartGroups(String eventId, String organizationId,
            OrgRegistrationStartGroupModificationRequest request) {
        // 단체 계정으로만 인증하며 참가자의 비밀번호를 사용하지 않는다.
        Organization organization = organizations.findModificationTarget(eventId, organizationId)
                .orElseThrow(() -> new CustomException(ErrorCode.ORGANIZATION_NOT_FOUND));
        RegistrationAccessVerifier.verifyOrganizationPassword(organization, request.password(), passwordEncoder);
        if (request.registrations() == null || request.registrations().isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_START_GROUP_SELECTION);
        }
        Set<String> ids = new HashSet<>();
        for (OrgRegistrationStartGroupModificationRequest.Participant participant : request.registrations()) {
            if (participant == null || participant.registrationId() == null || participant.registrationId().isBlank()) {
                throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET);
            }
            if (!ids.add(participant.registrationId())) {
                throw new CustomException(ErrorCode.DUPLICATE_REGISTRATION_MODIFICATION_TARGET);
            }
        }

        // 필요한 참가자만 한 번에 읽고 대회·단체 소속을 모두 확인한다. 목록 정렬은 추가하지 않는다.
        Map<String, Registration> members = registrations.findAllById(ids).stream()
                .collect(Collectors.toMap(Registration::getId, Function.identity()));
        List<RegistrationStartGroupResolver.Input> inputs = new ArrayList<>();
        for (OrgRegistrationStartGroupModificationRequest.Participant participant : request.registrations()) {
            Registration member = members.get(participant.registrationId());
            if (member == null || !Objects.equals(member.getEvent().getId(), eventId)
                    || member.getOrganization() == null
                    || !Objects.equals(member.getOrganization().getId(), organizationId)) {
                throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET);
            }
            validateRegistrationTarget(member);
            inputs.add(new RegistrationStartGroupResolver.Input(member.getEventCategory().getId(),
                    participant.startGroupId(), participant.startGroupUnselected()));
        }

        // 전원 검증 후 그룹 두 필드만 반영한다. 요청에서 빠진 참가자는 유지한다.
        List<RegistrationStartGroupResolver.Selection> selections = startGroups.resolveRegistrationStartGroups(inputs);
        for (int index = 0; index < selections.size(); index++) {
            applyRegistrationStartGroup(members.get(request.registrations().get(index).registrationId()),
                    selections.get(index));
        }
        registrations.flush();
    }

    /** 취소·만료·삭제된 신청은 그룹 변경 대상에서 제외한다. 결제 방식이나 기한은 검사하지 않는다. */
    private void validateRegistrationTarget(Registration registration) {
        if (registration.isSoftDeleted() || registration.getStatus() == RegistrationStatus.CANCELED
                || registration.getStatus() == RegistrationStatus.CANCELLATION_PENDING
                || registration.getStatus() == RegistrationStatus.EXPIRED) {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET);
        }
    }

    /** 동일 선택은 변경하지 않고 읽은 version만 검사하며 실제 변경은 JPA version 갱신을 따른다. */
    private void applyRegistrationStartGroup(Registration registration, RegistrationStartGroupResolver.Selection selection) {
        if (Objects.equals(registration.getStartGroupId(), selection.startGroupId())
                && registration.isStartGroupUnselected() == selection.unselected()) {
            entityManager.lock(registration, LockModeType.OPTIMISTIC);
            return;
        }
        registration.applyStartGroupSelection(selection.startGroupId(), selection.unselected());
    }
}
