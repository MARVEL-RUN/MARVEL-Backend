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


    /** 단체 행만 선행 확보하는 공통 NOWAIT 규칙을 적용한다. */
    public void lockOrganizationWithoutWaiting(OrgRegistrationModificationAccessContext access) {
        kr.co.teambrain.marvelrun.user.event.command.application.support.OrganizationLockSupport
                .lockWithoutWaiting(entityManager, access.organization().getId());
    }

    /** 단체를 즉시 잠근 뒤 기존 인증·구성원 버전 보호를 이어가는 호출 계약을 유지한다. */
    public void protectWithoutWaiting(OrgRegistrationModificationAccessContext access) {
        lockOrganizationWithoutWaiting(access);
        protect(access);
    }

    /** 전체 경로는 단체 선행 잠금·Payment 잠금 후 호출하고, 개인정보 경로는 Event 없이 호출한다. */
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