package kr.co.teambrain.marvelrun.user.discount;

import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.discount.domain.DiscountLink;
import kr.co.teambrain.marvelrun.user.discount.domain.DiscountPolicy;
import kr.co.teambrain.marvelrun.user.discount.domain.DiscountPolicyCategory;
import kr.co.teambrain.marvelrun.user.discount.domain.ReferenceFilter;
import kr.co.teambrain.marvelrun.user.discount.repository.DiscountLinkRepository;
import kr.co.teambrain.marvelrun.user.discount.repository.DiscountPolicyCategoryRepository;
import kr.co.teambrain.marvelrun.user.discount.repository.DiscountPolicyRepository;
import kr.co.teambrain.marvelrun.user.discount.repository.ReferenceFilterRepository;
import kr.co.teambrain.marvelrun.user.discount.service.DiscountPolicyResolver;
import kr.co.teambrain.marvelrun.user.discount.service.LinkDiscountEligibilityService;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryCommandRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** 승인된 테스트 DB의 신규 DDL에서 정책·링크 참조를 검증하며 각 테스트는 롤백한다. */
@Tag("policy-db")
@DataJpaTest(properties = {
        "spring.datasource.url=${MARVELRUN_TEST_DB_URL}",
        "spring.datasource.username=${MARVELRUN_TEST_DB_USERNAME}",
        "spring.datasource.password=${MARVELRUN_TEST_DB_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=never",
        "spring.flyway.enabled=false", "spring.liquibase.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DiscountPolicyResolver.class, LinkDiscountEligibilityService.class})
class DiscountPolicyDatabaseTest {
    @Autowired private DiscountPolicyRepository policies;
    @Autowired private DiscountPolicyCategoryRepository targets;
    @Autowired private DiscountLinkRepository links;
    @Autowired private ReferenceFilterRepository filters;
    @Autowired private EventCategoryCommandRepository categories;
    @Autowired private DiscountPolicyResolver resolver;
    @Autowired private LinkDiscountEligibilityService eligibility;

    /** 동일 정책의 다른 링크가 서로 다른 필터를 유지하고 기간 종료 후 승계를 막는다. */
    @Test
    void persistedLinksSharePolicyAndKeepSeparateFilters() {
        DiscountPolicy policy = policies.saveAndFlush(policy());
        LocalDateTime start = LocalDateTime.of(2026, 10, 1, 0, 0);
        DiscountLink first = persistLink(policy, start);
        DiscountLink second = persistLink(policy, start);
        assertThat(first.getPolicyId()).isEqualTo(second.getPolicyId());
        assertThat(first.getReferenceFilterId()).isNotEqualTo(second.getReferenceFilterId());
        assertThat(eligibility.acquireRegistrationLink(first.getCode(), "test-marvelrun", true, start,
                resolver.resolveEventPolicies("test-marvelrun")).getId()).isEqualTo(first.getId());
        assertThat(eligibility.inheritOrganizationLink(first.getId(), "test-marvelrun", start.plusDays(1),
                resolver.resolveEventPolicies("test-marvelrun"))).isNull();
        assertThat(links.findById(first.getId())).isPresent();
    }

    /** ALL 정책에 개별 종목 행을 섞으면 설정 오류로 차단한다. */
    @Test
    void rejectsAmbiguousAllAndSelectedConfiguration() {
        DiscountPolicy policy = policies.saveAndFlush(policy());
        EventCategory category = categories.findByEvent_Id("test-marvelrun").stream().findFirst()
                .orElseThrow(() -> new AssertionError("test-marvelrun 종목 준비가 필요합니다."));
        targets.saveAndFlush(DiscountPolicyCategory.builder().policyId(policy.getId()).eventCategoryId(category.getId()).build());
        assertThatThrownBy(() -> resolver.resolveEventPolicies("test-marvelrun")).isInstanceOf(CustomException.class);
    }

    /** 테스트마다 UUID 자동 생성 정책을 저장한다. */
    private DiscountPolicy policy() {
        return DiscountPolicy.builder().eventId("test-marvelrun").name("통합 검증용 할인")
                .stage(DiscountModels.Stage.EXTERNAL).calculationMethod(DiscountModels.Method.PERCENT)
                .ratePercent(new BigDecimal("50")).applicationType(DiscountModels.ApplicationType.ORGANIZATION)
                .categoryScope(DiscountModels.CategoryScope.ALL).minimumGroupSize(5).build();
    }

    /** 실제 정책·필터 FK로 연결한 링크를 저장한다. */
    private DiscountLink persistLink(DiscountPolicy policy, LocalDateTime start) {
        ReferenceFilter filter = filters.saveAndFlush(ReferenceFilter.builder().eventId("test-marvelrun")
                .code("TEST_" + UUID.randomUUID().toString().replace("-", "")).displayName("검증 필터").build());
        return links.saveAndFlush(DiscountLink.builder().policyId(policy.getId()).referenceFilterId(filter.getId())
                .code(UUID.randomUUID().toString()).acquisitionStartAt(start).acquisitionEndAt(start.plusDays(1)).build());
    }
}
