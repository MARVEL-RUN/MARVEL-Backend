package kr.co.teambrain.marvelrun.user.event.command.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationPersonalInformationValidator;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 개인정보와 변경 없음 경로를 기존 신청의 정정으로 처리한다. 결제·예약·정원 서비스에 의존하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RegistrationPersonalInformationService {
    private final RegistrationPersonalInformationValidator validator;
    private final RegistrationCommandRepository repository;
    private final EntityManager entityManager;

    /** 비교에 사용한 관리 엔티티의 version을 보호하고 금융 재정산 없이 저장 상태를 반환한다. */
    public RegistrationModificationSettlementResult modify(RegistrationModificationAccessContext access,
                                                          RegistrationModificationClassifier.Change change) {
        validator.validate(access);
        Registration registration = access.registration();
        if (change == RegistrationModificationClassifier.Change.PERSONAL_INFORMATION) {
            registration.applyPersonalInformation(access.request());
            // UPDATE의 기존 @Version 조건이 전체 수정·결제 반영과의 오래된 저장을 차단한다.
            repository.flush();
        } else if (change == RegistrationModificationClassifier.Change.NONE) {
            // dirty UPDATE가 없는 요청도 커밋 전에 읽은 version을 검사한다. version은 증가시키지 않는다.
            entityManager.lock(registration, LockModeType.OPTIMISTIC);
        } else {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT);
        }
        return new RegistrationModificationSettlementResult(
                List.of(new RegistrationModificationSettlementResult.Member(registration.getId(),
                        registration.getStatus(), registration.getContractAmount(), registration.getPaidAmount(),
                        registration.getContractAmount().subtract(registration.getPaidAmount()))), List.of());
    }
}
