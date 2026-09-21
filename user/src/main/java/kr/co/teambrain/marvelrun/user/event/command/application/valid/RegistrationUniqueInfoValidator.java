package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 신청 수정 경로가 공유하는 활성 uniqueInfo 사전 검증이다. 최종 동시 중복은 DB 고유 제약이 차단한다. */
@Component
@RequiredArgsConstructor
public class RegistrationUniqueInfoValidator {
    private final RegistrationCommandRepository repository;

    /** 동일 대회의 다른 활성 신청만 검사하고 자신과 삭제 이력은 제외한다. */
    public void validateOtherActive(String eventId, String registrationId, String name, String phNum, String birth) {
        if (repository.existsOtherActiveByEventIdAndUniqueInfo(eventId, registrationId, name, phNum, birth)) {
            throw new CustomException(ErrorCode.REGISTRATION_ALREADY_EXISTS);
        }
    }
}
