package kr.co.teambrain.marvelrun.admin.event;

import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualRosterModels.Course;
import kr.co.teambrain.marvelrun.admin.event.query.repository.VirtualRosterQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.VirtualRosterQueryRepository.PackingRegistration;
import kr.co.teambrain.marvelrun.admin.event.query.repository.VirtualRosterQueryRepository.PrintEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 DB에서 인쇄·패킹 명단의 대상 선정과 정렬을 검증하고 테스트 종료 시 롤백한다. */
@EnabledIfEnvironmentVariable(named = "MARVELRUN_VIRTUAL_ROSTER_DB_TEST", matches = "true")
@ActiveProfiles("admin-refund-test")
@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=${MARVELRUN_TEST_DB_URL}",
        "spring.datasource.username=${MARVELRUN_TEST_DB_USERNAME}",
        "spring.datasource.password=${MARVELRUN_TEST_DB_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=never",
        "spring.flyway.enabled=false", "spring.liquibase.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(VirtualRosterQueryRepository.class)
class VirtualRosterDatabaseTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 1, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 11, 20, 17, 0);

    @Autowired JdbcTemplate jdbc;
    @Autowired VirtualRosterQueryRepository repository;

    private String eventId;
    private String pkg;
    private String catA;
    private String catB;

    /** 버추얼 대회와 패키지(하위 종목 둘)를 만든다. */
    @BeforeEach
    void fixture() {
        eventId = event("VIRTUAL");
        pkg = category(eventId, "패키지", 3, "PACKAGE", null);
        catA = category(eventId, "5km | A", 1, "SINGLE", pkg);
        catB = category(eventId, "B", 2, "SINGLE", pkg);
    }

    /** 항목은 배번 길이·값 순이고 배번 없는 항목은 끝이며, 삭제·미확정·구간 밖·다른 대회는 빠지고 외부 결제는 들어간다. */
    @Test
    void printEntriesFilterAndOrder() {
        String other = event("VIRTUAL");
        String otherCat = category(other, "남의 종목", 1, "SINGLE", null);
        String r1 = registration(eventId, catA, "CONFIRMED", false, false, START);                   // 시작 포함
        String r2 = registration(eventId, pkg, "CONFIRMED", false, false, END.minusSeconds(1));
        String r3 = registration(eventId, catA, "CONFIRMED", false, true, START.plusDays(1));       // 외부 결제 포함
        registration(eventId, catA, "CONFIRMED", false, false, END);                                 // 끝 제외
        registration(eventId, catA, "CONFIRMED", false, false, START.minusSeconds(1));
        String deleted = registration(eventId, catA, "CONFIRMED", true, false, START.plusDays(1));
        String pending = registration(eventId, catA, "PAYMENT_PENDING", false, false, START.plusDays(1));
        String foreign = registration(other, otherCat, "CONFIRMED", false, false, START.plusDays(1));
        entry(eventId, r1, catA, "0002", "가");
        entry(eventId, r2, catA, "100", "나");
        entry(eventId, r2, catB, null, "다");
        entry(eventId, r3, catA, "9", "라");
        entry(eventId, r3, catB, "10", "마");
        entry(eventId, deleted, catA, "21", "삭제");
        entry(eventId, pending, catA, "22", "미확정");
        entry(other, foreign, otherCat, "1", "타대회");

        List<PrintEntry> rows = repository.findPrintEntries(eventId, START, END);

        assertThat(rows).extracting(PrintEntry::name).containsExactly("라", "마", "나", "가", "다");
        assertThat(rows).extracting(PrintEntry::bib).containsExactly("9", "10", "100", "0002", null);
        assertThat(rows.getFirst().categoryName()).isEqualTo("5km | A");
    }

    /** 신청 하나가 한 행이며 신청일시 순이고, 항목은 신청 ID 묶음으로 읽는다. */
    @Test
    void packingRegistrationsAndEntries() {
        String late = registration(eventId, catA, "CONFIRMED", false, false, START.plusDays(3));
        String early = registration(eventId, pkg, "CONFIRMED", false, true, START.plusDays(1));
        registration(eventId, pkg, "REFUNDED", false, false, START.plusDays(2));
        registration(eventId, pkg, "CONFIRMED", true, false, START.plusDays(2));
        registration(eventId, pkg, "CONFIRMED", false, false, END);
        entry(eventId, early, catA, "1", "가");
        entry(eventId, early, catB, "2", "나");
        entry(eventId, late, catA, "3", "다");

        List<PackingRegistration> rows = repository.findPackingRegistrations(eventId, START, END);

        assertThat(rows).extracting(PackingRegistration::id).containsExactly(early, late);
        assertThat(rows).extracting(PackingRegistration::productType).containsExactly("PACKAGE", "SINGLE");
        assertThat(repository.findPackingEntries(List.of(early, late))).hasSize(3);
        assertThat(repository.findPackingEntries(List.of())).isEmpty();
    }

    /** 범위 끝이 null이면 그쪽을 막지 않는다. 1차는 시작 전 신청까지, 마지막 차수는 마감 뒤 신청까지 담고 상향 대기 신청은 뺀다. */
    @Test
    void openEndedRangesIncludeEarlyAndLateRegistrations() {
        String before = registration(eventId, catA, "CONFIRMED", false, true, START.minusDays(1));
        String inRound = registration(eventId, catA, "CONFIRMED", false, false, START.plusDays(1));
        String after = registration(eventId, catA, "CONFIRMED", false, true, END.plusDays(30));
        String upgrading = registration(eventId, catA, "ADDITIONAL_PAYMENT_REQUIRED", false, false, START.plusDays(2));
        entry(eventId, before, catA, "1", "전");
        entry(eventId, inRound, catA, "2", "중");
        entry(eventId, after, catA, "3", "후");
        entry(eventId, upgrading, catA, "4", "상향");

        assertThat(repository.findPackingRegistrations(eventId, null, END)).extracting(PackingRegistration::id)
                .containsExactly(before, inRound);
        assertThat(repository.findPackingRegistrations(eventId, END, null)).extracting(PackingRegistration::id)
                .containsExactly(after);
        assertThat(repository.findPrintEntries(eventId, null, null)).extracting(PrintEntry::name)
                .containsExactly("전", "중", "후");
    }

    /** 하위 종목만 정렬 순서대로 읽고 이벤트 형식을 돌려준다. */
    @Test
    void childCoursesAndEvent() {
        assertThat(repository.findChildCourses(eventId)).extracting(Course::categoryId).containsExactly(catA, catB);
        assertThat(repository.findEvent(eventId)).hasValueSatisfying(e -> assertThat(e.format()).isEqualTo("VIRTUAL"));
        assertThat(repository.findEvent("nope")).isEmpty();
    }

    /** 패키지에 묶이지 않은 단품 종목도 열이 되고, 상품 유형이 없는 종목은 빠진다. */
    @Test
    void childCoursesIncludeStandaloneSingle() {
        String other = event("VIRTUAL");
        String standalone = category(other, "단독 단품", 1, "SINGLE", null);
        category(other, "오프라인식", 2, null, null);
        String r = registration(other, standalone, "CONFIRMED", false, false, START.plusDays(1));
        entry(other, r, standalone, "7", "단독");

        assertThat(repository.findChildCourses(other)).extracting(Course::categoryId).containsExactly(standalone);
        assertThat(repository.findPackingEntries(List.of(r))).hasSize(1);
    }

    private String event(String format) {
        String id = id();
        jdbc.update("""
                insert into event(id,name_kr,start_date,region,host,organizer,event_status,visible_status,
                    regist_start_date,regist_deadline,payment_deadline,auto_max_regist,auto_start,auto_deadline,phone_auth_required,event_format)
                values(?, '명단 테스트', ?, 'test','test','test','OPEN','OPEN',?,?,NULL,true,true,true,false,?)
                """, id, END.plusDays(7), START, LocalDateTime.of(2027, 1, 19, 23, 59, 59), format);
        return id;
    }

    private String category(String event, String name, long order, String productType, String parent) {
        String id = id();
        jdbc.update("insert into event_category(id,event_id,amount,name,is_active,sort_order,distance_meters,product_type,parent_category_id) values(?,?,40000,?,true,?,5000,?,?)",
                id, event, name, order, productType, parent);
        jdbc.update("INSERT INTO event_category_start_group (id,event_category_id,code,description,sort_order,min_pace_seconds_per_km,min_expected_finish_seconds,is_active) VALUES (UUID(),?,'ALL','버추얼런',10,0,0,1)", id);
        return id;
    }

    private String registration(String event, String categoryId, String status, boolean deleted, boolean external, LocalDateTime date) {
        String id = id();
        String startGroup = jdbc.queryForObject("select id from event_category_start_group where event_category_id=?", String.class, categoryId);
        jdbc.update("""
                insert into registration(id,event_id,event_category_id,start_group_id,start_group_unselected,name,ph_num,birth,gender,password,
                    address,address_detail,souvenir_json,status,is_del,external_payment,contract_amount,paid_amount,
                    terms_essential_agreed,terms_marketing_agreed,terms_marketing_channel_agreed,terms_agreed_at,registration_date)
                values(?,?,?,?,1,?,'010-0000-0000','1990-01-01','M','fixture','주소_01234','상세','[]',?,?,?,40000,40000,1,0,0,?,?)
                """, id, event, categoryId, startGroup, "신청자-" + id.substring(0, 8), status, deleted, external, START, date);
        return id;
    }

    private void entry(String event, String registrationId, String categoryId, String bib, String name) {
        jdbc.update("""
                insert into registration_entry(id,event_id,registration_id,event_category_id,name,ph_num,birth,gender,bib_number,created_at,updated_at)
                values(?,?,?,?,?,'010-0000-0000','1990-01-01','M',?,NOW(6),NOW(6))
                """, id(), event, registrationId, categoryId, name, bib);
    }

    private String id() {
        return UUID.randomUUID().toString();
    }
}
