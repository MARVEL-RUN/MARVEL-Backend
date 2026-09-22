package kr.co.teambrain.marvelrun.admin.payment.command;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** NOWAIT 예외 변환이 다른 SQL 장애를 경합으로 은폐하지 않는지 검증한다. */
class AdminRefundLockRepositoryTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final AdminRefundLockRepository repository = new AdminRefundLockRepository(jdbc);

    /** MySQL 3572만 사용자와 동일한 경합 오류로 변환한다. */
    @Test
    void mapsOnlyMysqlNowaitConflict() {
        var conflict = new DataAccessResourceFailureException("nowait", new SQLException("busy", "HY000", 3572));
        when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenThrow(conflict);
        assertThatThrownBy(() -> repository.lockOrganization("e", "o")).isInstanceOfSatisfying(CustomException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));
        var other = new DataAccessResourceFailureException("sql", new SQLException("deadlock", "40001", 1213));
        when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenThrow(other);
        assertThatThrownBy(() -> repository.lockOrganization("e", "o")).isSameAs(other);
    }

    /** 기간 필터 없이 정확한 대회·단체 행에 NOWAIT 잠금을 요청한다. */
    @Test
    void locksScopedParentsAndHandlesMissingRows() {
        when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of("e"));
        assertThat(repository.lockEvent("e")).isTrue();
        verify(jdbc).queryForList("select id from event where id=:id for update nowait", Map.of("id", "e"), String.class);
        when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of());
        assertThat(repository.lockOrganization("e", "o")).isFalse();
        verify(jdbc).queryForList("select id from organization where id=:id and event_id=:eventId for update nowait",
                Map.of("id", "o", "eventId", "e"), String.class);
    }

    /** 빈 부모 목록에서는 잘못된 IN 절이나 불필요한 취소 조회를 만들지 않는다. */
    @Test
    void emptyPaymentScopeDoesNotQueryCancellations() {
        assertThat(repository.lockCancellations(List.of())).isEmpty();
        verifyNoInteractions(jdbc);
    }
}
