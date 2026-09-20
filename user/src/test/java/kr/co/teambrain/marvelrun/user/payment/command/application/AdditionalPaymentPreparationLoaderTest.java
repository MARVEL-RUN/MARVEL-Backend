package kr.co.teambrain.marvelrun.user.payment.command.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOWAIT 오류 변환만 검증하며 실제 SQL·잠금 순서·DB 동시성 검증을 대신하지 않는다. */
class AdditionalPaymentPreparationLoaderTest {
    /** MySQL의 즉시 잠금 실패는 재시도 가능한 업무 충돌로 변환한다. */
    @Test
    void translatesOnlyMysqlNowaitError() {
        EntityManager manager = managerFailingWith(new RuntimeException(new SQLException("busy", "HY000", 3572)));
        AdditionalPaymentPreparationLoader loader = new AdditionalPaymentPreparationLoader(manager);
        assertThatThrownBy(() -> loader.lockPersonal("event", "registration"))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));
    }

    /** 실제 데드락이나 SQL 장애를 일반 NOWAIT 오류로 숨기지 않는다. */
    @Test
    void doesNotMaskOtherDatabaseErrors() {
        RuntimeException failure = new RuntimeException(new SQLException("deadlock", "40001", 1213));
        EntityManager manager = managerFailingWith(failure);
        AdditionalPaymentPreparationLoader loader = new AdditionalPaymentPreparationLoader(manager);
        assertThatThrownBy(() -> loader.lockPersonal("event", "registration")).isSameAs(failure);
    }

    /** 쿼리 실행 시 지정 오류를 발생시키는 대역을 완성한 뒤 반환한다. */
    private EntityManager managerFailingWith(RuntimeException failure) {
        EntityManager manager = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(manager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenThrow(failure);
        return manager;
    }
}