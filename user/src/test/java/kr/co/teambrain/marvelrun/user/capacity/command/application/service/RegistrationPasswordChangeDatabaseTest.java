package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationPasswordChangeRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.PersonalPasswordChangeRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.OrgRegistrationCreateResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationPasswordChangeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/** 기존 capacity-db 환경에서 대상 필터·변경 원자성·동시 요청의 최신 해시 검증을 확인한다. */
@Import(RegistrationPasswordChangeService.class)
class RegistrationPasswordChangeDatabaseTest extends CapacityMvpTestSupport {

    @Autowired
    private RegistrationPasswordChangeService passwordChanges;

    @Autowired
    private PasswordEncoder encoder;

    /** 다른 대회·삭제 신청·단체 소속 신청은 실제 저장소 조건으로 제외한다. */
    @Test
    void excludesWrongEventDeletedAndOrganizationMembers() {
        String personalId = personal(categoryA, "S", "1990-01-01").registrationId();
        OrgRegistrationCreateResponse group = group(categoryA);
        String memberId = group.registrationIds().getFirst();
        PersonalPasswordChangeRequest request = new PersonalPasswordChangeRequest("Test1234!", "NewPassword1!");
        String originalHash = s("select password from registration where id = ?", personalId);

        expectError(ErrorCode.REGISTRATION_NOT_FOUND,
                () -> passwordChanges.changePersonal("other-event", personalId, request));
        expectError(ErrorCode.REGISTRATION_NOT_FOUND,
                () -> passwordChanges.changePersonal(eventId, memberId, request));
        expectError(ErrorCode.ORGANIZATION_NOT_FOUND,
                () -> passwordChanges.changeOrganization("other-event", group.organizationId(),
                        new OrganizationPasswordChangeRequest("Test1234!", "NewPassword1!")));

        jdbc.update("update registration set is_del = 1 where id = ?", personalId);

        expectError(ErrorCode.REGISTRATION_NOT_FOUND,
                () -> passwordChanges.changePersonal(eventId, personalId, request));
        assertThat(s("select password from registration where id = ?", personalId)).isEqualTo(originalHash);
    }

    /** 구성원이 모두 삭제되어도 단체 계정은 변경할 수 있고 구성원 해시는 보존된다. */
    @Test
    void changesOrganizationWithoutActiveMembers() {
        OrgRegistrationCreateResponse group = group(categoryA);
        String memberId = group.registrationIds().getFirst();
        String memberHash = s("select password from registration where id = ?", memberId);

        jdbc.update("update registration set is_del = 1 where organization_id = ?", group.organizationId());

        passwordChanges.changeOrganization(eventId, group.organizationId(),
                new OrganizationPasswordChangeRequest("Test1234!", "NewPassword1!"));

        String stored = s("select password from organization where id = ?", group.organizationId());
        assertThat(encoder.matches("NewPassword1!", stored)).isTrue();
        assertThat(encoder.matches("Test1234!", stored)).isFalse();
        assertThat(s("select password from registration where id = ?", memberId)).isEqualTo(memberHash);
    }

    /** 두 요청이 같은 기존 비밀번호로 동시에 변경하면 하나만 성공하고 나머지는 인증 실패한다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyOneConcurrentChangeCanUseOldPassword(boolean organization) throws Exception {
        String targetId = organization
                ? group(categoryA).organizationId()
                : personal(categoryA, "S", "1990-01-01").registrationId();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Boolean> first = workers.submit(
                    () -> changeAfterStart(organization, targetId, "FirstPassword1!", ready, start));
            Future<Boolean> second = workers.submit(
                    () -> changeAfterStart(organization, targetId, "SecondPassword2!", ready, start));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            boolean firstSucceeded = first.get(30, TimeUnit.SECONDS);
            boolean secondSucceeded = second.get(30, TimeUnit.SECONDS);
            assertThat(firstSucceeded).isNotEqualTo(secondSucceeded);

            String table = organization ? "organization" : "registration";
            String stored = s("select password from " + table + " where id = ?", targetId);
            String winner = firstSucceeded ? "FirstPassword1!" : "SecondPassword2!";

            assertThat(encoder.matches(winner, stored)).isTrue();
            assertThat(encoder.matches("Test1234!", stored)).isFalse();
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(35, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 두 독립 서비스 트랜잭션을 동시에 시작하고 예상 인증 실패만 실패 결과로 집계한다. */
    private boolean changeAfterStart(
            boolean organization,
            String targetId,
            String newPassword,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();

        try {
            if (organization) {
                passwordChanges.changeOrganization(eventId, targetId,
                        new OrganizationPasswordChangeRequest("Test1234!", newPassword));
            } else {
                passwordChanges.changePersonal(eventId, targetId,
                        new PersonalPasswordChangeRequest("Test1234!", newPassword));
            }

            return true;
        } catch (CustomException exception) {
            assertThat(exception.getErrorCode()).isEqualTo(
                    organization ? ErrorCode.ORGANIZATION_ACCESS_DENIED : ErrorCode.REGISTRATION_ACCESS_DENIED);

            return false;
        }
    }
}
