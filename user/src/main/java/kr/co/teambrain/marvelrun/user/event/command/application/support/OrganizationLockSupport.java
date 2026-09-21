package kr.co.teambrain.marvelrun.user.event.command.application.support;

import jakarta.persistence.EntityManager;
import java.sql.SQLException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;

/** 단체 개인정보 수정과 금융 처리에서 같은 NOWAIT 잠금 규칙을 사용한다. */
public final class OrganizationLockSupport {
    /** 상태를 보관하지 않는 공통 잠금 도구의 생성을 막는다. */
    private OrganizationLockSupport() { }

    /** 단체 행만 즉시 확보하고 MySQL NOWAIT 충돌만 업무 오류로 변환한다. */
    public static void lockWithoutWaiting(EntityManager entityManager, String organizationId) {
        try {
            entityManager.createNativeQuery(
                            "select id from organization where id = :id for update nowait")
                    .setParameter("id", organizationId).getSingleResult();
        } catch (RuntimeException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && sql.getErrorCode() == 3572) {
                    throw new CustomException(ErrorCode.CONCURRENT_MODIFICATION);
                }
            }
            throw exception;
        }
    }
}