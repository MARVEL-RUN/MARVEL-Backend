package kr.co.teambrain.marvelrun.admin.event;

import kr.co.teambrain.marvelrun.admin.capacity.command.application.service.RegistrationCapacityService;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.VirtualRoundsRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.VirtualRoundsResponse;
import kr.co.teambrain.marvelrun.admin.event.command.application.service.VirtualRoundService;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationActionPolicyReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** 실제 DB에서 차수 교체가 정책 행을 지우고 다시 만드는 결과를 검증하고 테스트 종료 시 롤백한다. */
@EnabledIfEnvironmentVariable(named = "MARVELRUN_VIRTUAL_ROUND_DB_TEST", matches = "true")
@ActiveProfiles("admin-refund-test")
@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=${MARVELRUN_TEST_DB_URL}",
        "spring.datasource.username=${MARVELRUN_TEST_DB_USERNAME}",
        "spring.datasource.password=${MARVELRUN_TEST_DB_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=never",
        "spring.flyway.enabled=false", "spring.liquibase.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({VirtualRoundService.class, RegistrationCapacityService.class, RegistrationActionPolicyReader.class})
class VirtualRoundDatabaseTest {
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 11, 1, 0, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 11, 20, 17, 0);
    private static final LocalDateTime T3 = LocalDateTime.of(2026, 11, 23, 0, 0);
    private static final LocalDateTime T4 = LocalDateTime.of(2026, 11, 27, 17, 0);
    private static final LocalDateTime END = LocalDateTime.of(2027, 1, 19, 23, 59, 59);

    @Autowired JdbcTemplate jdbc;
    @Autowired VirtualRoundService service;
    @MockitoBean ServerTimeProvider time;
    private String eventId;

    @BeforeEach
    void fixture() {
        when(time.currentDateTime()).thenReturn(LocalDateTime.of(2026, 11, 5, 10, 0));
        eventId = UUID.randomUUID().toString();
        jdbc.update("""
                insert into event(id,name_kr,start_date,region,host,organizer,event_status,visible_status,
                    regist_start_date,regist_deadline,payment_deadline,auto_max_regist,auto_start,auto_deadline,phone_auth_required,event_format)
                values(?, '차수 테스트', ?, 'test','test','test','OPEN','OPEN',?,?,NULL,true,true,true,false,'VIRTUAL')
                """, eventId, T4, T1, END);
    }

    private static VirtualRoundsRequest request(LocalDateTime... times) {
        return new VirtualRoundsRequest(java.util.stream.IntStream.range(0, times.length / 2)
                .mapToObj(i -> new VirtualRoundsRequest.Round(times[2 * i], times[2 * i + 1])).toList());
    }

    private List<Map<String, Object>> rows() {
        return jdbc.queryForList("select id, action_type, registration_start_at, registration_end_at, effective_from, enabled from registration_action_policy where event_id=? order by action_type, registration_start_at", eventId);
    }

    /** 처음 만들면 차수 세 행씩과 사이·뒤 REGIST 행이 생기고 조회와 같은 결과를 돌려준다. */
    @Test
    void createsRoundsAndRegistBlocks() {
        VirtualRoundsResponse response = service.replaceRounds(eventId, request(T1, T2, T3, T4));

        assertThat(response.rounds()).extracting(VirtualRoundsResponse.Round::number, VirtualRoundsResponse.Round::startAt,
                VirtualRoundsResponse.Round::deadline, VirtualRoundsResponse.Round::closed)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, T1, T2, false), org.assertj.core.groups.Tuple.tuple(2, T3, T4, false));
        assertThat(service.getRounds(eventId)).isEqualTo(response);
        assertThat(rows()).hasSize(8);
        assertThat(rows().stream().filter(r -> "REGIST".equals(r.get("action_type"))).map(r -> (LocalDateTime) r.get("registration_start_at")))
                .containsExactlyInAnyOrder(T2, T4);
    }

    /** 마감된 차수의 행은 id까지 그대로 남고 나머지는 새로 만들어진다. */
    @Test
    void closedRoundRowsSurviveReplacement() {
        service.replaceRounds(eventId, request(T1, T2, T3, T4));
        List<Object> closedIds = jdbc.queryForList("select id from registration_action_policy where event_id=? and action_type<>'REGIST' and registration_start_at=?", Object.class, eventId, T1);
        when(time.currentDateTime()).thenReturn(T2.plusHours(1));

        LocalDateTime newDeadline = T4.plusDays(1);
        VirtualRoundsResponse response = service.replaceRounds(eventId, request(T1, T2, T3, newDeadline));

        assertThat(response.rounds().get(0).closed()).isTrue();
        assertThat(jdbc.queryForList("select id from registration_action_policy where event_id=? and action_type<>'REGIST' and registration_start_at=?", Object.class, eventId, T1))
                .containsExactlyInAnyOrderElementsOf(closedIds);
        assertThat(response.rounds().get(1).deadline()).isEqualTo(newDeadline);
        assertThat(rows()).hasSize(8);

        assertThatThrownBy(() -> service.replaceRounds(eventId, request(T3, newDeadline)))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VIRTUAL_ROUND_CLOSED_IMMUTABLE));
    }
}
