package kr.co.teambrain.marvelrun.admin.event;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategoryStartGroup;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.repository.EventCategoryStartGroupRepository;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationSearchCondition;
import kr.co.teambrain.marvelrun.admin.event.query.dto.response.RegistrationDetailResponse;
import kr.co.teambrain.marvelrun.admin.event.query.dto.response.RegistrationListResponse;
import kr.co.teambrain.marvelrun.admin.event.query.service.RegistrationQueryService;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationActionPolicyReader;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** 실제 DB에서 관리자 그룹 검색의 페이지·count 및 상세 매핑을 검증하고 테스트 종료 시 롤백한다. */
@EnabledIfEnvironmentVariable(named = "MARVELRUN_START_GROUP_DB_TEST", matches = "true")
@ActiveProfiles("admin-refund-test")
@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=${MARVELRUN_TEST_DB_URL}",
        "spring.datasource.username=${MARVELRUN_TEST_DB_USERNAME}",
        "spring.datasource.password=${MARVELRUN_TEST_DB_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=never",
        "spring.flyway.enabled=false", "spring.liquibase.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RegistrationQueryService.class, RegistrationActionPolicyReader.class})
class RegistrationStartGroupDatabaseTest {
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RegistrationQueryService queries;
    @Autowired private EventCategoryStartGroupRepository groups;
    @MockitoBean private ServerTimeProvider time;
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 14, 12, 0);
    private String eventId;
    private String firstCategory;
    private String secondCategory;

    /** 생성 자료 전체를 기본 DataJpaTest 트랜잭션 안에 둔다. 커밋이나 별도 트랜잭션은 사용하지 않는다. */
    @BeforeEach
    void createFixtureCategories() {
        eventId = createEvent();
        firstCategory = createCategory(eventId);
        secondCategory = createCategory(eventId);
        when(time.currentDateTime()).thenReturn(now);
    }

    /** 동일 code의 서로 다른 그룹 UUID를 모두 찾고 대회 필터와 count를 유지한다. */
    @Test
    void codeSearchAcrossCategoriesKeepsPaginationAndEventScope() {
        // 같은 대회의 두 종목과 다른 대회의 동일 코드 신청을 준비한다.
        String firstGroup = createGroup(firstCategory, "A", true);
        String secondGroup = createGroup(secondCategory, "A", true);
        createRegistration(eventId, firstCategory, firstGroup, false, false);
        createRegistration(eventId, secondCategory, secondGroup, false, false);
        createRegistration(eventId, firstCategory, createGroup(firstCategory, "B", true), true, false);
        String otherEvent = createEvent();
        String otherCategory = createCategory(otherEvent);
        createRegistration(otherEvent, otherCategory, createGroup(otherCategory, "A", true), false, false);
        entityManager.flush();
        entityManager.clear();

        // 페이지 크기 1로 count 쿼리를 실행시키고 중복·누락 없는 결과를 확인한다.
        RegistrationSearchCondition condition = condition("A", null);
        Page<RegistrationListResponse> first = queries.getRegistrationList(condition, page(0, 1));
        Page<RegistrationListResponse> second = queries.getRegistrationList(condition, page(1, 1));
        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(second.getTotalElements()).isEqualTo(2);
        assertThat(first.getContent()).extracting(RegistrationListResponse::startGroupCode).containsExactly("A");
        assertThat(second.getContent()).extracting(RegistrationListResponse::startGroupCode).containsExactly("A");
        assertThat(first.getContent().getFirst().registrationId()).isNotEqualTo(second.getContent().getFirst().registrationId());
        assertThat(first.getContent().getFirst().listNumber()).isEqualTo(2L);
        assertThat(second.getContent().getFirst().listNumber()).isEqualTo(1L);
        assertThat(queries.getRegistrationList(condition("missing-code", null), page(0, 10))).isEmpty();
    }

    /** 동일 후미 그룹을 직접 선택한 신청과 미선택 보강 신청을 각각 검색한다. */
    @Test
    void unselectedSearchDistinguishesExplicitRearSelection() {
        String rear = createGroup(firstCategory, "B", true);
        Registration explicit = createRegistration(eventId, firstCategory, rear, false, false);
        Registration unselected = createRegistration(eventId, firstCategory, rear, true, false);
        entityManager.flush();
        entityManager.clear();

        assertThat(queries.getRegistrationList(condition("B", false), page(0, 10)))
                .extracting(RegistrationListResponse::registrationId).containsExactly(explicit.getId());
        assertThat(queries.getRegistrationList(condition("B", true), page(0, 10)))
                .extracting(RegistrationListResponse::registrationId).containsExactly(unselected.getId());
        assertThat(queries.getRegistrationList(condition(null, true), page(0, 10)).getTotalElements()).isEqualTo(1);
    }

    /** 비활성 기존 그룹도 조회·검색하며 기존 외부결제 정책 응답을 유지한다. */
    @Test
    void inactiveGroupRemainsVisibleInListAndDetail() {
        String inactive = createGroup(firstCategory, "B", false);
        Registration registration = createRegistration(eventId, firstCategory, inactive, true, true);
        entityManager.flush();
        entityManager.clear();

        RegistrationListResponse row = queries.getRegistrationList(condition("B", true), page(0, 10)).getContent().getFirst();
        RegistrationDetailResponse detail = queries.getRegistrationDetail(registration.getId());
        assertThat(row.startGroupCode()).isEqualTo("B");
        assertThat(row.startGroupUnselected()).isTrue();
        assertThat(detail.startGroupId()).isEqualTo(inactive);
        assertThat(detail.startGroupCode()).isEqualTo("B");
        assertThat(detail.startGroupUnselected()).isTrue();
        assertThat(detail.externalPayment()).isTrue();
        assertThat(detail.userPolicy().modifyAllowed()).isFalse();
        assertThat(detail.userPolicy().modifyReason().code().name()).isEqualTo("EXTERNAL_PAYMENT");
    }

    /** 기존 정렬을 그대로 사용한다. */
    private PageRequest page(int number, int size) {
        return PageRequest.of(number, size, Sort.by(Sort.Order.desc("registrationDate"), Sort.Order.desc("id")));
    }

    /** 현재 테스트 대회에 한정된 검색 조건을 만든다. */
    private RegistrationSearchCondition condition(String code, Boolean unselected) {
        return new RegistrationSearchCondition(eventId, null, null, null, null, code, unselected);
    }

    /** 다른 데이터와 충돌하지 않는 UUID 대회를 생성한다. */
    private String createEvent() {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                insert into event(id,name_kr,start_date,region,host,organizer,event_status,visible_status,
                    regist_start_date,regist_deadline,payment_deadline,auto_max_regist,auto_start,auto_deadline,phone_auth_required)
                values(?,'그룹 조회 테스트',?,'test','test','test','OPEN','OPEN',?,?,?,true,true,true,false)
                """, id, now.plusMonths(1), now.minusDays(10), now.plusDays(10), now.plusDays(10));
        return id;
    }

    /** 종목명 추정 없이 필수 거리와 대회 소속을 지정한다. */
    private String createCategory(String targetEvent) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into event_category(id,event_id,amount,name,is_active,sort_order,distance_meters) values(?,?,40000,'테스트 종목',true,0,5000)", id, targetEvent);
        return id;
    }

    /** JPA UUID 생성과 실제 그룹 저장소를 사용한다. */
    private String createGroup(String categoryId, String code, boolean active) {
        return groups.saveAndFlush(EventCategoryStartGroup.builder().eventCategoryId(categoryId).code(code)
                .description("테스트 안내문").sortOrder("A".equals(code) ? 10 : 20)
                .minPaceSecondsPerKm("A".equals(code) ? 0 : 360)
                .minExpectedFinishSeconds("A".equals(code) ? 0 : 1800).active(active).build()).getId();
    }

    /** 조회에 필요한 신청만 생성하며 결제 주문이나 외부 서비스를 만들지 않는다. */
    private Registration createRegistration(String targetEvent, String categoryId, String groupId, boolean unselected, boolean external) {
        Registration registration = Registration.builder()
                .event(entityManager.getReference(Event.class, targetEvent))
                .eventCategory(entityManager.getReference(EventCategory.class, categoryId))
                .startGroupId(groupId).startGroupUnselected(unselected).externalPayment(external)
                .name("test-" + UUID.randomUUID().toString().substring(0, 8)).phNum("010-0000-0000").birth("1990-01-01")
                .gender(GenderClass.M).password("fixture").souvenirJson(List.of())
                .status(RegistrationStatus.CONFIRMED).contractAmount(new BigDecimal("40000")).paidAmount(new BigDecimal("40000"))
                .termsEssentialAgreed(true).termsMarketingAgreed(false).termsMarketingChannelAgreed(false).termsAgreedAt(now).build();
        entityManager.persist(registration);
        return registration;
    }
}
