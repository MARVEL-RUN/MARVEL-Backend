package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationModificationParticipantRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationPersonalInformationValidator;
import kr.co.teambrain.marvelrun.user.event.command.repository.OrganizationCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 단체 기존 구성원의 개인정보만 같은 트랜잭션에서 정정하고 주문·예약·정원·금융 상태는 보존한다. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class OrgRegistrationPersonalInformationService {
    private final OrgRegistrationModificationGuard guard;
    private final OrgRegistrationPersonalInformationValidator validator;
    private final RegistrationCommandRepository repository;

    /** 비교 당시 구성원과 version을 보호한 뒤 허용 개인정보만 반영하고 저장된 금융 요약을 반환한다. */
    public RegistrationModificationSettlementResult modify(OrgRegistrationModificationAccessContext access,
                                                          RegistrationModificationClassifier.Change change) {
        if (change == RegistrationModificationClassifier.Change.FULL) {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT);
        }
        guard.protect(access);
        validator.validate(access);
        Map<String, Registration> members = access.currentRegistrations().stream()
                .collect(Collectors.toMap(Registration::getId, Function.identity()));


        Organization organization = access.organization();
        boolean requestedConsent = access.request().guardianConsent();

        // 기존 동의 철회 차단
        if (organization.isGuardianConsent() && !requestedConsent) {
            throw new CustomException(ErrorCode.GUARDIAN_CONSENT_REQUIRED);
        }

        // 최초 동의 반영: 트랜잭션 커밋 시 저장
        if (!organization.isGuardianConsent() && requestedConsent) {
            organization.guardianConsentChecked();
        }


        boolean changed = false;
        for (OrgRegistrationModificationParticipantRequest request : access.request().registrations()) {
            Registration registration = members.get(request.registrationId());

            if (!Objects.equals(registration.getName(), request.name())
                    || !Objects.equals(registration.getEmail(), request.email())
                    || !Objects.equals(registration.getPhNum(), request.phNum())
                    || registration.getGender() != request.gender()) {

                registration.applyOrganizationPersonalInformation(request);
                changed = true;
            }
        }
        if (changed) {
            repository.flush();
        }
        return new RegistrationModificationSettlementResult(access.currentRegistrations().stream()
                .map(registration -> new RegistrationModificationSettlementResult.Member(registration.getId(),
                        registration.getStatus(), registration.getContractAmount(), registration.getPaidAmount(),
                        registration.getContractAmount().subtract(registration.getPaidAmount())))
                .toList(), List.of());
    }
}
