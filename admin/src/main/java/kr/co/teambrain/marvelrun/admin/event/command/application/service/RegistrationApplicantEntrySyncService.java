package kr.co.teambrain.marvelrun.admin.event.command.application.service;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.admin.event.command.repository.RegistrationEntryCommandRepository;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 관리자가 신청자 정보를 바꾼 뒤 단품 참가 항목을 신청자 값으로 맞춘다.
 *
 * 단품의 참가 항목은 신청자 값을 따른다(배번·기록증 이름). 패키지 받는 사람은 신청자와 따로이므로 건드리지 않는다.
 * 이미 신청이 있는 종목에 상품 종류를 붙여 항목이 없으면 신청자 값으로 만든다.
 * 사용자 서버의 RegistrationProductModificationService.syncApplicantEntry와 같은 규칙이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RegistrationApplicantEntrySyncService {

    private final RegistrationEntryCommandRepository registrationEntryCommandRepository;

    /** 신청자 정보를 반영한 뒤 호출한다. 배번은 그대로 둔다. */
    public void syncApplicantEntry(Registration registration) {
        if (registration.getEventCategory().getProductType() != ProductType.SINGLE) {
            return;
        }

        List<RegistrationEntry> entries = registrationEntryCommandRepository.findByRegistration_Id(registration.getId());
        if (entries.isEmpty()) {
            registrationEntryCommandRepository.save(RegistrationEntry.forApplicant(registration));
            return;
        }
        for (RegistrationEntry entry : entries) {
            entry.followApplicant(registration);
        }
    }
}
