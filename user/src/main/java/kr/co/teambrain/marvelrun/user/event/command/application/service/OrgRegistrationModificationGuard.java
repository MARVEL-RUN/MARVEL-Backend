package kr.co.teambrain.marvelrun.user.event.command.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationModificationAccessValidator;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.sql.SQLException;

/** 단체 전체 수정과 개인정보 정정 사이의 구성원 추가·제거 및 오래된 비교 결과를 보호한다. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class OrgRegistrationModificationGuard {
    private final EntityManager entityManager;
    private final RegistrationCommandRepository repository;
    private final OrgRegistrationModificationAccessValidator accessValidator;

    /** Event를 보유한 전체 수정은 단체 잠금을 기다리지 않아 개인정보 저장과의 대기 순환을 차단한다. */
    public void protectWithoutWaiting(OrgRegistrationModificationAccessContext access) {
        try {
            entityManager.createNativeQuery("select id from organization where id = :id for update nowait")
                    .setParameter("id", access.organization().getId())
                    .getSingleResult();
        } catch (RuntimeException exception) {
            // MySQL ER_LOCK_NOWAIT만 업무 충돌로 변환한다. 데드락·접속·SQL 오류를 숨기지 않는다.
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sqlException && sqlException.getErrorCode() == 3572) {
                    throw new CustomException(ErrorCode.CONCURRENT_MODIFICATION);
                }
            }
            throw exception;
        }
        // 이미 확보한 같은 단체 행을 refresh하여 인증정보도 현재 값으로 확인한다.
        protect(access);
    }

    /** 전체 경로는 Event 다음에, 개인정보 경로는 Event 없이 단체와 구성원만 잠근다. */
    public void protect(OrgRegistrationModificationAccessContext access) {
        Map<String, Long> observed = new HashMap<>();
        for (Registration registration : access.currentRegistrations()) {
            observed.put(registration.getId(), registration.getVersion());
        }
        entityManager.refresh(access.organization(), LockModeType.PESSIMISTIC_WRITE);
        accessValidator.validateAccess(access.organization(), access.request().access());
        if (!access.event().getId().equals(access.organization().getEvent().getId())) {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET);
        }
        Map<String, Long> current = new HashMap<>();
        // 엔티티 재조회는 1차 캐시/반복 읽기 snapshot을 반환할 수 있어 잠금 SELECT의 scalar 값을 비교한다.
        for (Object[] row : repository.lockActiveOrganizationVersions(
                access.event().getId(), access.organization().getId())) {
            current.put((String) row[0], ((Number) row[1]).longValue());
        }
        if (!observed.equals(current)) {
            throw new CustomException(ErrorCode.CONCURRENT_MODIFICATION);
        }
    }
}
