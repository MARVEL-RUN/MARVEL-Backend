package kr.co.teambrain.marvelrun.admin.event;

import kr.co.teambrain.marvelrun.admin.event.query.repository.VirtualRosterQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.VirtualRosterQueryRepository.PackingLine;
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

    /** 참가 항목 하나가 한 행이며 신청일시 순이다. 삭제·미확정·구간 밖은 빠지고 외부 결제는 들어간다. */
    @Test
    void packingLinesFilterAndRegistrationOrder() {
        String late = registration(eventId, catA, "CONFIRMED", false, false, START.plusDays(3));
        String early = registration(eventId, pkg, "CONFIRMED", false, true, START.plusDays(1));
        registration(eventId, pkg, "REFUNDED", false, false, START.plusDays(2));
        registration(eventId, pkg, "CONFIRMED", true, false, START.plusDays(2));
        registration(eventId, pkg, "CONFIRMED", false, false, END);
        entry(eventId, early, catA, "1", "가");
        entry(eventId, early, catB, "2", "나");
        entry(eventId, late, catA, "3", "다");

        List<PackingLine> rows = repository.findPackingLines(eventId, START, END);

        assertThat(rows).extracting(PackingLine::registrationId).containsExactly(early, early, late);
        assertThat(rows).extracting(PackingLine::productType).containsExactly("PACKAGE", "PACKAGE", "SINGLE");
        assertThat(rows).extracting(PackingLine::entryName).containsExactly("가", "나", "다");
        assertThat(rows.getFirst().receiver()).startsWith("신청자-");
        assertThat(rows.getFirst().address()).isEqualTo("주소_01234");
    }

    /**
     * 한 주문의 구매 줄은 줄 순서, 줄 안 항목은 종목 순서다. 같은 종목 단품 두 줄의 두 사람이 모두 나온다.
     * 상품·기념품은 그 줄 값이고, 항목 없는 구매 줄과 구매 줄이 없는 신청도 한 행이다.
     */
    @Test
    void packingLinesFollowItemOrderAndKeepEveryEntry() {
        String order = registration(eventId, catA, "CONFIRMED", false, false, START.plusDays(1));
        String first = item(order);
        String second = addItem(order, catA, 2, "[{\"souvenirId\":\"s2\"}]");
        String third = addItem(order, pkg, 3, "[{\"souvenirId\":\"s3\"}]");
        String empty = addItem(order, catB, 4, "[]");
        entryInItem(eventId, order, third, catB, null, "패키지B");
        entryInItem(eventId, order, third, catA, "11", "패키지A");
        entryInItem(eventId, order, second, catA, "12", "둘째");
        entryInItem(eventId, order, first, catA, "13", "첫째");
        String noItem = registration(eventId, catA, "CONFIRMED", false, false, START.plusDays(2));

        List<PackingLine> rows = repository.findPackingLines(eventId, START, END);

        assertThat(rows).extracting(PackingLine::itemId).containsExactly(first, second, third, third, empty, null);
        assertThat(rows).extracting(PackingLine::entryName).containsExactly("첫째", "둘째", "패키지A", "패키지B", null, null);
        assertThat(rows).extracting(PackingLine::entryCategoryName).containsExactly("5km | A", "5km | A", "5km | A", "B", null, null);
        // 행 기념품을 고를 때 쓰는 항목 종목 id다. 항목이 없는 행은 null이다.
        assertThat(rows).extracting(PackingLine::entryCategoryId).containsExactly(catA, catA, catA, catB, null, null);
        assertThat(rows).extracting(PackingLine::productName).containsExactly("5km | A", "5km | A", "패키지", "패키지", "B", "5km | A");
        assertThat(rows.get(1).souvenirJson()).contains("s2");
        assertThat(rows.get(2).souvenirJson()).contains("s3");
        assertThat(rows.getLast().registrationId()).isEqualTo(noItem);
    }

    /** 부분 취소한 줄과 그 받는 사람은 패킹 명단·인쇄 명단에 나오지 않는다. */
    @Test
    void canceledLinesAreLeftOutOfPackingAndPrint() {
        String order = registration(eventId, catA, "CONFIRMED", false, false, START.plusDays(1));
        String first = item(order);
        String second = addItem(order, catB, 2, "[{\"souvenirId\":\"s2\"}]");
        entryInItem(eventId, order, first, catA, "13", "취소한 상품");
        entryInItem(eventId, order, second, catB, "21", "남은 상품");
        jdbc.update("update registration_item set canceled_at = ? where id = ?", START.plusDays(2), first);

        assertThat(repository.findPackingLines(eventId, START, END))
                .extracting(PackingLine::itemId, PackingLine::entryName)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(second, "남은 상품"));
        assertThat(repository.findPrintEntries(eventId, START, END))
                .extracting(PrintEntry::name)
                .containsExactly("남은 상품");
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

        assertThat(repository.findPackingLines(eventId, null, END)).extracting(PackingLine::registrationId)
                .containsExactly(before, inRound);
        assertThat(repository.findPackingLines(eventId, END, null)).extracting(PackingLine::registrationId)
                .containsExactly(after);
        assertThat(repository.findPrintEntries(eventId, null, null)).extracting(PrintEntry::name)
                .containsExactly("전", "중", "후");
    }

    /** 대회 형식을 돌려주고 없는 대회는 비어 있다. */
    @Test
    void findsEvent() {
        assertThat(repository.findEvent(eventId)).hasValueSatisfying(e -> assertThat(e.format()).isEqualTo("VIRTUAL"));
        assertThat(repository.findEvent("nope")).isEmpty();
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
                insert into registration_entry(id,event_id,registration_id,registration_item_id,event_category_id,name,ph_num,birth,gender,bib_number,created_at,updated_at)
                values(?,?,?,?,?,?,'010-0000-0000','1990-01-01','M',?,NOW(6),NOW(6))
                """, id(), event, registrationId, item(registrationId), categoryId, name, bib);
    }

    /** 신청의 첫 구매 줄. 없으면 신청 종목·계약금액으로 만든다. 참가 항목은 구매 줄에 묶어야 한다. */
    private String item(String registrationId) {
        List<String> found = jdbc.queryForList(
                "select id from registration_item where registration_id=? and sort_order=1", String.class, registrationId);
        if (!found.isEmpty()) {
            return found.getFirst();
        }
        String itemId = UUID.randomUUID().toString();
        jdbc.update("""
                insert into registration_item(id,event_id,registration_id,event_category_id,sort_order,amount,souvenir_json)
                select ?,event_id,id,event_category_id,1,contract_amount,souvenir_json from registration where id=?
                """, itemId, registrationId);
        return itemId;
    }

    /** 신청에 구매 줄을 더한다. */
    private String addItem(String registrationId, String categoryId, int sortOrder, String souvenirJson) {
        String itemId = UUID.randomUUID().toString();
        jdbc.update("""
                insert into registration_item(id,event_id,registration_id,event_category_id,sort_order,amount,souvenir_json)
                select ?,event_id,id,?,?,40000,? from registration where id=?
                """, itemId, categoryId, sortOrder, souvenirJson, registrationId);
        return itemId;
    }

    /** 지정한 구매 줄에 참가 항목을 더한다. */
    private void entryInItem(String event, String registrationId, String itemId, String categoryId, String bib, String name) {
        jdbc.update("""
                insert into registration_entry(id,event_id,registration_id,registration_item_id,event_category_id,name,ph_num,birth,gender,bib_number,created_at,updated_at)
                values(?,?,?,?,?,?,'010-0000-0000','1990-01-01','M',?,NOW(6),NOW(6))
                """, id(), event, registrationId, itemId, categoryId, name, bib);
    }

    private String id() {
        return UUID.randomUUID().toString();
    }
}
