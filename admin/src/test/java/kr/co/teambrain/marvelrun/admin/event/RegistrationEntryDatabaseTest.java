package kr.co.teambrain.marvelrun.admin.event;

import jakarta.persistence.EntityManager;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.discount.query.ReferenceFilterQueryService;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationSearchCondition;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationStatDto;
import kr.co.teambrain.marvelrun.admin.event.query.dto.response.RegistrationDetailResponse;
import kr.co.teambrain.marvelrun.admin.event.query.dto.response.RegistrationEntryResponse;
import kr.co.teambrain.marvelrun.admin.event.query.dto.response.RegistrationListResponse;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationDailyReportQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationRowQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.report.RegistrationDailyReportRow;
import kr.co.teambrain.marvelrun.admin.event.query.service.RegistrationQueryService;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationActionPolicyReader;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationReservationHistoryResolver;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationRowSouvenirs;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** 실제 DB에서 신청 목록이 참가자(참가 항목)마다 한 행인지, 배번·종목·검색·기념품·상세와 통계가 행 기준인지 검증하고 롤백한다. */
@EnabledIfEnvironmentVariable(named = "MARVELRUN_REGISTRATION_ENTRY_DB_TEST", matches = "true")
@ActiveProfiles("admin-refund-test")
@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=${MARVELRUN_TEST_DB_URL}",
        "spring.datasource.username=${MARVELRUN_TEST_DB_USERNAME}",
        "spring.datasource.password=${MARVELRUN_TEST_DB_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=never",
        "spring.flyway.enabled=false", "spring.liquibase.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ReferenceFilterQueryService.class, RegistrationQueryService.class, RegistrationActionPolicyReader.class,
        RegistrationRowQueryRepository.class, RegistrationReservationHistoryResolver.class, RegistrationRowSouvenirs.class,
        RegistrationDailyReportQueryRepository.class})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
class RegistrationEntryDatabaseTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 12, 0);

    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired RegistrationQueryService queries;
    @Autowired RegistrationQueryRepository registrationQueries;
    @Autowired RegistrationDailyReportQueryRepository dailyReport;
    @Autowired kr.co.teambrain.marvelrun.admin.event.command.repository.RegistrationCommandRepository registrationCommands;
    @MockitoBean ServerTimeProvider time;

    private String eventId;
    private String pkg;
    private String catA;
    private String catB;
    private String catC;
    private Registration pkgRegistration;
    private Registration singleRegistration;

    /** 버추얼 대회에 패키지(하위 3종)와 단품 신청을 만들고 항목마다 배번을 둔다. */
    @BeforeEach
    void fixture() {
        when(time.currentDateTime()).thenReturn(NOW);
        eventId = event("VIRTUAL");
        pkg = category(eventId, "패키지", 4, "PACKAGE", null);
        catA = category(eventId, "5km | A", 1, "SINGLE", pkg);
        catB = category(eventId, "10km | B", 2, "SINGLE", pkg);
        catC = category(eventId, "3km | C", 3, "SINGLE", pkg);
        pkgRegistration = registration(eventId, pkg);
        singleRegistration = registration(eventId, catA);
        em.flush();
        // 하위 종목 정렬 순서(A, B, C)와 ID 순서가 다르도록 항목을 거꾸로 넣는다.
        entry(eventId, pkgRegistration, catC, "30001");
        entry(eventId, pkgRegistration, catB, "20001");
        entry(eventId, pkgRegistration, catA, "10001");
        entry(eventId, singleRegistration, catA, "10002");
        em.flush();
        em.clear();
    }

    /** 패키지 주문은 하위 종목마다 한 행이고, 건수는 행 수(참가자 수)다. 배번은 하위 종목 순서로 각 행에 나온다. */
    @Test
    void listCountsParticipantRowsAndShowsEachBibOnItsRow() {
        Page<RegistrationListResponse> page = queries.getRegistrationList(condition(eventId, null), page());

        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getContent()).hasSize(4);
        assertThat(bibs(page, pkgRegistration)).containsExactly("10001", "20001", "30001");
        assertThat(bibs(page, singleRegistration)).containsExactly("10002");
        List<RegistrationListResponse> packageRows = rows(page, pkgRegistration);
        assertThat(packageRows).extracting(RegistrationListResponse::courseName)
                .containsExactly("5km | A", "10km | B", "3km | C");
        assertThat(packageRows).extracting(RegistrationListResponse::productName).containsOnly("패키지");
        assertThat(packageRows).extracting(RegistrationListResponse::entryId).doesNotContainNull().doesNotHaveDuplicates();
    }

    /** 단품 두 줄(같은 종목)과 패키지 한 줄이 있는 한 주문은 참가자 다섯 행이고, 행마다 자기 구매 줄을 가리킨다. */
    @Test
    void sameCategoryLinesAndPackageLineGiveOneRowPerParticipant() {
        Registration multi = registration(eventId, catA);
        em.flush();
        String first = item(multi.getId());
        String second = line(multi.getId(), catA, 2);
        String packageLine = line(multi.getId(), pkg, 3);
        entryOn(eventId, multi, first, catA, "40001");
        entryOn(eventId, multi, second, catA, "40002");
        entryOn(eventId, multi, packageLine, catA, "40003");
        entryOn(eventId, multi, packageLine, catB, "40004");
        entryOn(eventId, multi, packageLine, catC, "40005");
        em.flush();
        em.clear();

        Page<RegistrationListResponse> page = queries.getRegistrationList(condition(eventId, null), page());

        assertThat(page.getTotalElements()).isEqualTo(9);
        assertThat(bibs(page, multi)).containsExactly("40001", "40002", "40003", "40004", "40005");
        assertThat(rows(page, multi)).extracting(RegistrationListResponse::itemId)
                .containsExactly(first, second, packageLine, packageLine, packageLine);
    }

    /** 종목 필터는 참가자 종목 또는 구매 상품이 맞는 행을 찾는다. 패키지 상품으로 거르면 패키지 행 세 개가 나온다. */
    @Test
    void categoryFilterMatchesParticipantCategoryOrProductPerRow() {
        assertThat(bibs(queries.getRegistrationList(categoryCondition(catB), page()))).containsExactly("20001");
        assertThat(bibs(queries.getRegistrationList(categoryCondition(pkg), page())))
                .containsExactlyInAnyOrder("10001", "20001", "30001");
        assertThat(bibs(queries.getRegistrationList(categoryCondition(catA), page())))
                .containsExactlyInAnyOrder("10001", "10002");
    }

    /** 검색어는 주문자 이름이 맞으면 그 주문의 모든 행을, 받는 사람 이름이 맞으면 그 행만 남긴다. */
    @Test
    void keywordMatchesOrdererForAllRowsAndRecipientForOneRow() {
        jdbc.update("update registration set name = '주문자김' where id = ?", pkgRegistration.getId());
        jdbc.update("update registration_entry set name = '받는홍' where bib_number = '20001'");

        assertThat(queries.getRegistrationList(keywordCondition("주문자김"), page()).getTotalElements()).isEqualTo(3);
        Page<RegistrationListResponse> recipient = queries.getRegistrationList(keywordCondition("받는홍"), page());
        assertThat(recipient.getTotalElements()).isEqualTo(1);
        assertThat(bibs(recipient)).containsExactly("20001");
    }

    /** 행의 기념품은 그 행 종목에 연결된 것만 준다. 참가 항목이 없는 행은 구매 줄 기념품 전부다. */
    @Test
    void rowSouvenirFollowsParticipantCategoryLink() {
        String kitA = souvenir(eventId, "A키트");
        String kitB = souvenir(eventId, "B키트");
        String kitC = souvenir(eventId, "C키트");
        link(catA, kitA);
        link(catB, kitB);
        link(catC, kitC);
        setLineSouvenirs(pkgRegistration.getId(), kitA, kitB, kitC);
        setLineSouvenirs(singleRegistration.getId(), kitA);
        Registration noEntries = registration(eventId, pkg);
        em.flush();
        item(noEntries.getId());
        setLineSouvenirs(noEntries.getId(), kitA, kitB);
        em.flush();
        em.clear();

        Page<RegistrationListResponse> page = queries.getRegistrationList(condition(eventId, null), page());

        assertThat(rows(page, pkgRegistration)).extracting(RegistrationListResponse::souvenirName)
                .containsExactly("A키트", "B키트", "C키트");
        assertThat(rows(page, singleRegistration)).extracting(RegistrationListResponse::souvenirName)
                .containsExactly("A키트");
        assertThat(rows(page, noEntries)).extracting(RegistrationListResponse::souvenirName)
                .containsExactly("A키트 | B키트");
    }

    /** 부문에 연결되지 않은 공용 기념품은 그 주문의 모든 참가자 행에 나온다. */
    @Test
    void sharedSouvenirShowsOnEveryParticipantRow() {
        String shared = souvenir(eventId, "공용키트");
        link(catA, shared);
        link(catB, shared);
        link(catC, shared);
        setLineSouvenirs(pkgRegistration.getId(), shared);
        em.flush();
        em.clear();

        Page<RegistrationListResponse> page = queries.getRegistrationList(condition(eventId, null), page());

        assertThat(rows(page, pkgRegistration)).extracting(RegistrationListResponse::souvenirName)
                .containsExactly("공용키트", "공용키트", "공용키트");
    }

    /** 신청 상세의 구매 줄은 상품 종류를 돌려주고, 참가 항목은 itemId로 그 줄을 가리킨다. */
    @Test
    void detailReturnsItemsAndEntriesPointToThem() {
        RegistrationDetailResponse packageDetail = queries.getRegistrationDetail(pkgRegistration.getId());
        assertThat(packageDetail.items()).hasSize(1);
        RegistrationDetailResponse.ItemResponse packageItem = packageDetail.items().getFirst();
        assertThat(packageItem.productType()).isEqualTo(ProductType.PACKAGE);
        assertThat(packageDetail.entries()).extracting(RegistrationEntryResponse::bibNumber)
                .containsExactly("10001", "20001", "30001");
        assertThat(packageDetail.entries()).extracting(RegistrationEntryResponse::itemId)
                .containsOnly(packageItem.itemId());

        RegistrationDetailResponse singleDetail = queries.getRegistrationDetail(singleRegistration.getId());
        assertThat(singleDetail.items()).hasSize(1);
        assertThat(singleDetail.items().getFirst().productType()).isEqualTo(ProductType.SINGLE);
        assertThat(singleDetail.entries()).extracting(RegistrationEntryResponse::bibNumber)
                .containsExactly("10002");
    }

    /** 통계와 일일 보고서는 참가자마다 한 행이다. 패키지 종목은 열이 되지 않고 하위 종목으로 센다. */
    @Test
    void statisticsAndDailyReportRowsCountParticipants() {
        assertThat(registrationQueries.findStatsByEventId(eventId)).hasSize(4);

        List<RegistrationDailyReportRow> rows = dailyReport.findReportRows(eventId, NOW.minusDays(30), NOW.plusDays(400));
        assertThat(rows).extracting(RegistrationDailyReportRow::courseName)
                .containsExactlyInAnyOrder("5km | A", "10km | B", "3km | C", "5km | A");
    }

    /** 참가 항목이 없는 패키지 신청도 빼지 않고 신청 종목(패키지) 한 행으로 센다. */
    @Test
    void packageWithoutEntriesIsCountedAsPackageCourse() {
        Registration noEntries = registration(eventId, pkg);
        em.flush();
        item(noEntries.getId());
        em.flush();

        List<RegistrationDailyReportRow> rows = dailyReport.findReportRows(eventId, NOW.minusDays(30), NOW.plusDays(400));
        assertThat(rows).hasSize(5);
        assertThat(rows).extracting(RegistrationDailyReportRow::courseName).containsOnlyOnce("패키지");

        assertThat(registrationQueries.findStatsByEventId(eventId)).hasSize(5)
                .extracting(RegistrationStatDto::courseName).containsOnlyOnce("패키지");
    }

    /**
     * 같은 신청자 주문 여러 건 허용 신청의 신원 검사용 잠금 읽기는 본인과 삭제 이력을 빼고 같은 신원의 살아 있는 신청만 센다.
     * duplicate_allowed 행은 유니크 인덱스에서 빠지므로 같은 신원 두 행을 만들 수 있다.
     */
    @Test
    void lockingIdentityCountIgnoresSelfAndDeletedRows() {
        jdbc.update("update registration set duplicate_allowed = 1, name = '같은신청자' where id in (?, ?)",
                pkgRegistration.getId(), singleRegistration.getId());

        assertThat(registrationCommands.countOtherActiveByEventIdAndUniqueInfoForShare(
                eventId, pkgRegistration.getId(), "같은신청자", "010-0000-0000", "1990-01-01")).isEqualTo(1);
        jdbc.update("update registration set is_del = 1 where id = ?", singleRegistration.getId());
        assertThat(registrationCommands.countOtherActiveByEventIdAndUniqueInfoForShare(
                eventId, pkgRegistration.getId(), "같은신청자", "010-0000-0000", "1990-01-01")).isZero();
    }

    /** 삭제된 패키지 신청은 상세를 볼 수 있어도 받는 사람을 고칠 수 없다. */
    @Test
    void deletedPackageIsNotEditable() {
        jdbc.update("update registration set is_del=1 where id=?", pkgRegistration.getId());

        assertThat(queries.getRegistrationDetail(pkgRegistration.getId()).entriesEditable()).isFalse();
    }

    /** 신청 상세의 참가 항목은 entryId와 배번을 하위 종목 순서로 돌려주고, 상품 신청(단품·패키지)은 받는 사람을 고칠 수 있다. */
    @Test
    void detailReturnsEntriesInCategoryOrder() {
        RegistrationDetailResponse response = queries.getRegistrationDetail(pkgRegistration.getId());

        assertThat(response.entriesEditable()).isTrue();
        assertThat(response.entries()).hasSize(3);
        assertThat(response.entries()).allSatisfy(entry -> assertThat(entry.entryId()).isNotBlank());
        assertThat(response.entries()).extracting(RegistrationEntryResponse::bibNumber)
                .containsExactly("10001", "20001", "30001");
        assertThat(response.entries()).extracting(RegistrationEntryResponse::eventCategoryId)
                .containsExactly(catA, catB, catC);
        // 단품 받는 사람도 신청자 값을 따르지 않으므로 여기서 고친다.
        assertThat(queries.getRegistrationDetail(singleRegistration.getId()).entriesEditable()).isTrue();
    }

    /** 배번은 정확히 일치할 때만 찾고, 찾은 행은 그 참가자의 배번만 보여 준다. 다른 대회의 같은 배번은 잡히지 않는다. */
    @Test
    void bibSearchMatchesExactlyWithinEvent() {
        String otherEvent = event("VIRTUAL");
        String otherCategory = category(otherEvent, "다른 대회 종목", 1, "SINGLE", null);
        Registration other = registration(otherEvent, otherCategory);
        em.flush();
        entry(otherEvent, other, otherCategory, "20001");
        em.flush();
        em.clear();

        Page<RegistrationListResponse> found = queries.getRegistrationList(condition(eventId, "20001"), page());
        assertThat(found.getTotalElements()).isEqualTo(1);
        assertThat(found.getContent()).extracting(RegistrationListResponse::registrationId)
                .containsExactly(pkgRegistration.getId());
        // 검색된 행에는 그 참가자의 배번만 보인다.
        assertThat(found.getContent().getFirst().bibNumbers()).containsExactly("20001");

        assertThat(queries.getRegistrationList(condition(eventId, "2000"), page())).isEmpty();
        Page<RegistrationListResponse> otherFound = queries.getRegistrationList(condition(otherEvent, "20001"), page());
        assertThat(otherFound.getContent()).extracting(RegistrationListResponse::registrationId)
                .containsExactly(other.getId());
    }

    /** 오프라인 대회의 목록은 배번이 비어 있고 정상 조회된다. */
    @Test
    void offlineEventListHasNoBibNumbers() {
        String offline = event("OFFLINE");
        String category = category(offline, "오프라인 종목", 1, null, null);
        registration(offline, category);
        em.flush();
        em.clear();

        Page<RegistrationListResponse> page = queries.getRegistrationList(condition(offline, null), page());

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().getFirst().bibNumbers()).isEmpty();
    }

    /** 한 신청의 행들을 목록 순서대로 돌려준다. */
    private List<RegistrationListResponse> rows(Page<RegistrationListResponse> page, Registration registration) {
        return page.getContent().stream().filter(row -> row.registrationId().equals(registration.getId())).toList();
    }

    private List<String> bibs(Page<RegistrationListResponse> page, Registration registration) {
        return rows(page, registration).stream().flatMap(row -> row.bibNumbers().stream()).toList();
    }

    private List<String> bibs(Page<RegistrationListResponse> page) {
        return page.getContent().stream().flatMap(row -> row.bibNumbers().stream()).toList();
    }

    private RegistrationSearchCondition condition(String event, String bib) {
        return new RegistrationSearchCondition(event, null, null, null, null, null, null, null, bib);
    }

    private RegistrationSearchCondition categoryCondition(String categoryId) {
        return new RegistrationSearchCondition(eventId, null, categoryId, null, null, null, null, null, null);
    }

    private RegistrationSearchCondition keywordCondition(String keyword) {
        return new RegistrationSearchCondition(eventId, null, null, null, keyword, null, null, null, null);
    }

    private PageRequest page() {
        return PageRequest.of(0, 10, Sort.by(Sort.Order.desc("registrationDate"), Sort.Order.desc("id")));
    }

    private String event(String format) {
        String id = id();
        jdbc.update("""
                insert into event(id,name_kr,start_date,region,host,organizer,event_status,visible_status,
                    regist_start_date,regist_deadline,payment_deadline,auto_max_regist,auto_start,auto_deadline,phone_auth_required,event_format)
                values(?, '배번 목록 테스트', ?, 'test','test','test','OPEN','OPEN',?,?,NULL,true,true,true,false,?)
                """, id, NOW.plusDays(7), NOW.minusDays(10), NOW.plusDays(30), format);
        return id;
    }

    private String category(String event, String name, long order, String productType, String parent) {
        String id = id();
        jdbc.update("insert into event_category(id,event_id,amount,name,is_active,sort_order,distance_meters,product_type,parent_category_id) values(?,?,40000,?,true,?,5000,?,?)",
                id, event, name, order, productType, parent);
        jdbc.update("INSERT INTO event_category_start_group (id,event_category_id,code,description,sort_order,min_pace_seconds_per_km,min_expected_finish_seconds,is_active) VALUES (UUID(),?,'ALL','테스트',10,0,0,1)", id);
        return id;
    }

    private Registration registration(String event, String categoryId) {
        Registration row = Registration.builder()
                .startGroupId(jdbc.queryForObject("select id from event_category_start_group where event_category_id=?", String.class, categoryId))
                .startGroupUnselected(true).event(em.getReference(Event.class, event)).eventCategory(em.getReference(EventCategory.class, categoryId))
                .name("test-" + id().substring(0, 8)).phNum("010-0000-0000").birth("1990-01-01")
                .gender(GenderClass.M).password("fixture").address("주소").addressDetail("상세").souvenirJson(List.of())
                .status(RegistrationStatus.CONFIRMED).softDeleted(false).externalPayment(false)
                .contractAmount(new BigDecimal("40000")).paidAmount(new BigDecimal("40000"))
                .termsEssentialAgreed(true).termsMarketingAgreed(false).termsMarketingChannelAgreed(false).termsAgreedAt(NOW).build();
        em.persist(row);
        return row;
    }

    private void entry(String event, Registration registration, String categoryId, String bib) {
        entryOn(event, registration, item(registration.getId()), categoryId, bib);
    }

    /** 지정한 구매 줄에 참가 항목을 넣는다. */
    private void entryOn(String event, Registration registration, String itemId, String categoryId, String bib) {
        jdbc.update("""
                insert into registration_entry(id,event_id,registration_id,registration_item_id,event_category_id,name,ph_num,birth,gender,bib_number,bib_issued_at,created_at,updated_at)
                values(?,?,?,?,?,'받는사람','010-0000-0000','1990-01-01','M',?,NOW(6),NOW(6),NOW(6))
                """, id(), event, registration.getId(), itemId, categoryId, bib);
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

    /** 신청에 지정한 순서의 구매 줄을 더한다. 종목 값은 신청 종목과 같게 두고, 참가 항목은 따로 넣는다. */
    private String line(String registrationId, String categoryId, int sortOrder) {
        String itemId = UUID.randomUUID().toString();
        jdbc.update("""
                insert into registration_item(id,event_id,registration_id,event_category_id,sort_order,amount,souvenir_json)
                select ?,event_id,id,?,?,40000,'[]' from registration where id=?
                """, itemId, categoryId, sortOrder, registrationId);
        return itemId;
    }

    private String souvenir(String event, String name) {
        String id = id();
        jdbc.update("insert into souvenir(id,name,sizes,is_active,event_id,sort_order) values(?,?,'S|M|L',1,?,1)",
                id, name, event);
        return id;
    }

    private void link(String categoryId, String souvenirId) {
        jdbc.update("insert into event_category_souvenir(id,event_category_id,souvenir_id,created_at) values(?,?,?,NOW(6))",
                id(), categoryId, souvenirId);
    }

    /** 신청의 첫 구매 줄에 기념품 선택(사이즈 M)을 넣는다. */
    private void setLineSouvenirs(String registrationId, String... souvenirIds) {
        String json = java.util.Arrays.stream(souvenirIds)
                .map(souvenirId -> "{\"souvenirId\":\"" + souvenirId + "\",\"selectedSize\":\"M\"}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        jdbc.update("update registration_item set souvenir_json = ? where registration_id = ? and sort_order = 1",
                json, registrationId);
    }

    private String id() {
        return UUID.randomUUID().toString();
    }
}
