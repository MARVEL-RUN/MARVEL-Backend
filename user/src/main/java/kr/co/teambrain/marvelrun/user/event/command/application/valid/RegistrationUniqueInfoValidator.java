package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 신청 수정 경로가 공유하는 활성 uniqueInfo 사전 검증이다. 최종 동시 중복은 DB 고유 제약이 차단한다. */
@Component
@RequiredArgsConstructor
public class RegistrationUniqueInfoValidator {
    private final RegistrationCommandRepository repository;
    private final EventCommandRepository eventRepository;

    /** 동일 대회의 다른 활성 신청만 검사하고 자신과 삭제 이력은 제외한다. */
    public void validateOtherActive(String eventId, String registrationId, String name, String phNum, String birth) {
        if (repository.existsOtherActiveByEventIdAndUniqueInfo(eventId, registrationId, name, phNum, birth)) {
            throw new CustomException(ErrorCode.REGISTRATION_ALREADY_EXISTS);
        }
    }

    /**
     * 개인 신청 수정의 신원(이름·연락처·생년월일)을 검사한다.
     *
     * 같은 신청자 주문 여러 건을 허용한 대회의 신청(duplicate_allowed)은 DB 유니크 인덱스가 보지 않는다.
     * 그래서 신원이 그대로면 같은 신청자의 다른 주문이 있어도 통과시키고,
     * 신원을 바꿀 때만 신청 생성과 같은 대회 행 잠금을 잡은 뒤 바꿀 신원의 다른 활성 주문을 찾는다.
     * 그 밖의 신청은 지금처럼 항상 다른 활성 신청을 검사한다(최종 중복은 DB 유니크 인덱스가 막는다).
     */
    public void validatePersonalIdentity(Registration current, String name, String phNum, String birth) {
        String eventId = current.getEvent().getId();
        if (!current.isDuplicateAllowed()) {
            validateOtherActive(eventId, current.getId(), name, phNum, birth);
            return;
        }
        if (Objects.equals(current.getName(), name)
                && Objects.equals(current.getPhNum(), phNum)
                && Objects.equals(current.getBirth(), birth)) {
            return;
        }
        eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new CustomException(ErrorCode.EVENT_NOT_FOUND));
        // 잠금 전에 이미 읽은 스냅숏이 아니라 커밋된 최신 행을 본다.
        if (repository.countOtherActiveByEventIdAndUniqueInfoForShare(
                eventId, current.getId(), name, phNum, birth) > 0) {
            throw new CustomException(ErrorCode.REGISTRATION_ALREADY_EXISTS);
        }
    }
}
