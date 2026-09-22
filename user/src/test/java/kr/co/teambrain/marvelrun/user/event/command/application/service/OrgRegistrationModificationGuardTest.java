package kr.co.teambrain.marvelrun.user.event.command.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationModificationAccessValidator;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 전체 경로의 대기 없는 잠금과 정확한 MySQL 오류 변환 범위를 검증한다. */
class OrgRegistrationModificationGuardTest {
    private final EntityManager entityManager = mock(EntityManager.class);
    private final RegistrationCommandRepository repository = mock(RegistrationCommandRepository.class);
    private final OrgRegistrationModificationAccessValidator validator = mock(OrgRegistrationModificationAccessValidator.class);
    private final OrgRegistrationModificationGuard guard = new OrgRegistrationModificationGuard(entityManager, repository, validator);
    private final Query query = mock(Query.class, RETURNS_SELF);
    private final Event event = Event.builder().id("event").build();
    private final Organization organization = Organization.builder().id("organization").event(event).build();
    private final OrgRegistrationModificationAccessContext access = new OrgRegistrationModificationAccessContext(
            event, organization, List.of(), new OrgRegistrationModificationRequest(false, 
                    new OrganizationAccessRequest("test", "test-only"), List.of()), LocalDateTime.of(2026, 9, 20, 12, 0));

    /** NOWAIT 성공 이후에만 인증정보 refresh와 구성원 현재 읽기로 이어진다. */
    @Test
    void successfulImmediateLockContinuesExistingProtection() {
        prepareQuery();
        when(query.getSingleResult()).thenReturn("organization");
        when(repository.lockActiveOrganizationVersions("event", "organization")).thenReturn(List.of());
        guard.protectWithoutWaiting(access);
        InOrder order = inOrder(query, entityManager, validator, repository);
        order.verify(query).getSingleResult();
        order.verify(entityManager).refresh(organization, LockModeType.PESSIMISTIC_WRITE);
        order.verify(validator).validateAccess(organization, access.request().access());
        order.verify(repository).lockActiveOrganizationVersions("event", "organization");
    }

    /** 잠금이 이미 점유된 NOWAIT 오류만 기존 동시 수정 업무 오류로 변환한다. */
    @Test
    void occupiedOrganizationIsConcurrentModification() {
        prepareQuery();
        when(query.getSingleResult()).thenThrow(new PersistenceException(new SQLException("test lock occupied", "HY000", 3572)));
        assertThatThrownBy(() -> guard.protectWithoutWaiting(access)).isInstanceOfSatisfying(CustomException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));
        verify(entityManager, never()).refresh(any(), any(LockModeType.class));
        verifyNoInteractions(repository, validator);
    }

    /** 실제 데드락·대기 시간 초과·SQL 오류는 예상한 충돌로 위장하지 않는다. */
    @ParameterizedTest
    @ValueSource(ints = {1213, 1205, 1064})
    void otherDatabaseErrorsAreNotMasked(int errorCode) {
        prepareQuery();
        PersistenceException failure = new PersistenceException(new SQLException("test database failure", "HY000", errorCode));
        when(query.getSingleResult()).thenThrow(failure);
        assertThatThrownBy(() -> guard.protectWithoutWaiting(access)).isSameAs(failure);
        verifyNoInteractions(repository, validator);
    }

    /** 실제 구현의 MySQL NOWAIT SQL과 단체 식별자 바인딩을 연결한다. */
    private void prepareQuery() {
        when(entityManager.createNativeQuery("select id from organization where id = :id for update nowait")).thenReturn(query);
    }
}
