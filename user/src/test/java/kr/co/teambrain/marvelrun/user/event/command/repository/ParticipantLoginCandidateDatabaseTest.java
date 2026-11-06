package kr.co.teambrain.marvelrun.user.event.command.repository;

import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 주문자 키 채움 SQL(#133) 뒤 기존 개인 온라인 신청이 로그인 후보로 읽히는지 확인한다. 변경은 롤백된다. */
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
class ParticipantLoginCandidateDatabaseTest {

    /** 배포 문서의 채움 SQL과 같은 문장이다. */
    private static final String BACKFILL = """
            UPDATE registration SET orderer_key = id
             WHERE organization_id IS NULL AND external_payment = 0 AND orderer_key IS NULL
            """;

    @Autowired private RegistrationCommandRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestEntityManager entityManager;

    /** 채움 전(주문자 키 없음)에는 로그인 후보가 아니고, 채운 뒤에는 자기 id를 주문자 키로 후보가 된다. */
    @Test
    void backfilledPersonalRegistrationBecomesLoginCandidate() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, event_id, name, ph_num, birth from registration
                where organization_id is null and external_payment = 0
                order by registration_date desc
                limit 1
                """);
        assertThat(rows).as("개인 온라인 신청이 하나 이상 필요합니다").hasSize(1);
        Map<String, Object> row = rows.getFirst();
        String id = (String) row.get("id");

        jdbc.update("update registration set orderer_key = null where id = ?", id);
        entityManager.clear();
        assertThat(candidateIds(row)).doesNotContain(id);

        jdbc.update(BACKFILL);
        entityManager.clear();
        assertThat(candidateIds(row)).contains(id);
        assertThat(repository.findById(id).orElseThrow().getOrdererKey()).isEqualTo(id);
    }

    private List<String> candidateIds(Map<String, Object> row) {
        return repository.findParticipantLoginCandidates((String) row.get("event_id"), (String) row.get("name"),
                        (String) row.get("ph_num"), (String) row.get("birth")).stream()
                .map(Registration::getId)
                .toList();
    }
}
