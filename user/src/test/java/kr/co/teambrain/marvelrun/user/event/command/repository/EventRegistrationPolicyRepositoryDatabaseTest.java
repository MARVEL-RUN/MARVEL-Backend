package kr.co.teambrain.marvelrun.user.event.command.repository;

import kr.co.teambrain.marvelrun.user.event.command.application.domain.policy.EventRegistrationPolicy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** 공개 조회·관리 허용 컬럼이 실제 DB의 기본값과 0·1 그대로 정책 엔티티에 읽히는지 확인한다. 변경은 롤백된다. */
@Tag("policy-db")
@DataJpaTest(properties = {
        "spring.datasource.url=${MARVELRUN_TEST_DB_URL}",
        "spring.datasource.username=${MARVELRUN_TEST_DB_USERNAME}",
        "spring.datasource.password=${MARVELRUN_TEST_DB_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=false",
        "spring.liquibase.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EventRegistrationPolicyRepositoryDatabaseTest {

    private static final String EVENT_ID = "test-marvelrun";

    @Autowired private EventRegistrationPolicyRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestEntityManager entityManager;

    /** 컬럼 기본값은 1(허용)이라 설정을 따로 두지 않은 대회는 지금처럼 공개 조회·관리를 한다. */
    @Test
    void columnDefaultsToEnabled() {
        String columnDefault = jdbc.queryForObject("""
                select column_default from information_schema.columns
                where table_schema = database()
                  and table_name = 'event_registration_policy'
                  and column_name = 'public_registration_access_enabled'
                """, String.class);

        assertThat(columnDefault).isEqualTo("1");
    }

    /** 0과 1이 그대로 엔티티 값으로 읽힌다. 인터셉터는 이 값으로 공개 API를 막는다. */
    @Test
    void readsStoredValue() {
        assertThat(readAfterSetting(0)).isFalse();
        assertThat(readAfterSetting(1)).isTrue();
    }

    private boolean readAfterSetting(int value) {
        int updated = jdbc.update(
                "update event_registration_policy set public_registration_access_enabled = ? where event_id = ?",
                value, EVENT_ID);
        assertThat(updated).as("대회 정책 행이 필요합니다: " + EVENT_ID).isEqualTo(1);
        entityManager.clear();
        return repository.findByEventId(EVENT_ID)
                .map(EventRegistrationPolicy::isPublicRegistrationAccessEnabled)
                .orElseThrow();
    }
}
