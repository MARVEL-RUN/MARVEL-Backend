package kr.co.teambrain.marvelrun.user.discount.service;

import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.discount.domain.DiscountLink;
import kr.co.teambrain.marvelrun.user.discount.domain.DiscountPolicy;
import kr.co.teambrain.marvelrun.user.discount.repository.DiscountLinkRepository;
import kr.co.teambrain.marvelrun.user.discount.repository.DiscountPolicyRepository;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 신규 링크 취득 경계와 단체 신규원 승계의 기간 차이를 검증한다. */
class LinkDiscountEligibilityServiceTest {
    private final DiscountLinkRepository links = mock(DiscountLinkRepository.class);
    private final DiscountPolicyRepository policies = mock(DiscountPolicyRepository.class);
    private final DiscountPolicyResolver resolver = mock(DiscountPolicyResolver.class);
    private final LinkDiscountEligibilityService service = new LinkDiscountEligibilityService(links, policies, resolver);
    private final LocalDateTime start = LocalDateTime.of(2026, 10, 1, 0, 0);
    private final DiscountLink link = DiscountLink.builder().id("link").policyId("policy").code("Crew")
        .referenceFilterId("filter").acquisitionStartAt(start).acquisitionEndAt(start.plusDays(1)).build();
    private final DiscountModels.Policy policy = new DiscountModels.Policy("policy", "event", 1,
        DiscountModels.Stage.EXTERNAL, DiscountModels.Method.PERCENT, new BigDecimal("50"), null,
        DiscountModels.ApplicationType.ORGANIZATION,
        DiscountModels.CategoryScope.SELECTED,
        Set.of("eligible"),
        5,
        null,
        null);

    /** 코드 없는 요청에 필수 링크 검증을 강요하지 않는다. */
    @Test
    void absentCodeUsesNormalRegistration() {
        assertThat(service.acquireRegistrationLink(null, "event", true, start, List.of())).isNull();
        assertThat(service.acquireRegistrationLink("  ", "event", true, start, List.of())).isNull();
        verifyNoInteractions(links, policies, resolver);
    }

    /** 종목은 자격 취득 조건이 아니며 시작 포함·종료 제외로 판단한다. */
    @Test
    void startsInclusiveAndEndsExclusive() {
        prepareLink();
        assertThat(service.acquireRegistrationLink(" Crew ", "event", true, start, List.of(policy))).isSameAs(link);
        assertThatThrownBy(() -> service.acquireRegistrationLink("Crew", "event", true, start.plusDays(1), List.of(policy)))
            .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> service.acquireRegistrationLink("Crew", "event", false, start, List.of(policy)))
            .isInstanceOf(CustomException.class);
    }

    /** 기간 종료 후 새 구성원만 미취득 처리하며 기존 저장 링크를 삭제하지 않는다. */
    @Test
    void expiredGroupDoesNotGrantNewQualification() {
        prepareLink();
        when(resolver.resolveStoredLink("link")).thenReturn(link);
        assertThat(service.inheritOrganizationLink("link", "event", start, List.of(policy))).isSameAs(link);
        assertThat(service.inheritOrganizationLink("link", "event", start.plusDays(1), List.of(policy))).isNull();
        assertThat(link.getReferenceFilterId()).isEqualTo("filter");
    }

    /** 잘못된 코드가 일반 신청으로 조용히 전환되지 않는다. */
    @Test
    void unknownCodeFails() {
        assertThatThrownBy(() -> service.acquireRegistrationLink("unknown", "event", true, start, List.of()))
            .isInstanceOf(CustomException.class);
    }

    /** 비활성 링크는 신규 취득을 차단하고 단체 신규원에게 승계하지 않는다. */
    @Test
    void killSwitchBlocksAcquisitionAndInheritance() {
        DiscountLink disabled = DiscountLink.builder().id("link").policyId("policy").code("Crew")
            .referenceFilterId("filter").enabled(false)
            .acquisitionStartAt(start).acquisitionEndAt(start.plusDays(1)).build();
        when(links.findByCode("Crew")).thenReturn(Optional.of(disabled));
        when(policies.findById("policy")).thenReturn(Optional.of(DiscountPolicy.builder().eventId("event").build()));
        when(resolver.resolveLinkPolicy(disabled, "event", List.of(policy))).thenReturn(policy);
        when(resolver.resolveStoredLink("link")).thenReturn(disabled);

        assertThatThrownBy(() -> service.acquireRegistrationLink("Crew", "event", true, start, List.of(policy)))
            .isInstanceOfSatisfying(CustomException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.REGISTRATION_DISCOUNT_LINK_UNAVAILABLE));
        assertThat(service.inheritOrganizationLink("link", "event", start, List.of(policy))).isNull();
        assertThat(disabled.getReferenceFilterId()).isEqualTo("filter");
    }

    /** 중단 링크의 기존 미결제자는 새 최초 주문을 만들 수 없으며 기간은 재검사하지 않는다. */
    @Test
    void killSwitchValidatesStoredInitialPaymentLinks() {
        Registration pending = Registration.builder().discountLinkId("link").build();
        when(links.findAllById(List.of("link"))).thenReturn(List.of(
            DiscountLink.builder().id("link").enabled(false).build()));
        assertThatThrownBy(() -> service.validateInitialPaymentLinks(List.of(pending)))
            .isInstanceOfSatisfying(CustomException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DISCOUNT_LINK_PAYMENT_DISABLED));

        // 재활성화되면 취득 기간이 과거여도 저장된 자격으로 새 주문을 준비할 수 있다.
        when(links.findAllById(List.of("link"))).thenReturn(List.of(link));
        assertThatCode(() -> service.validateInitialPaymentLinks(List.of(pending))).doesNotThrowAnyException();
    }

    /** 코드가 없는 일반 주문과 최초 납부 대상이 없는 호출은 링크를 조회하지 않는다. */
    @Test
    void killSwitchDoesNotQueryLinksForOrdinaryPayment() {
        service.validateInitialPaymentLinks(List.of(Registration.builder().build()));
        service.validateInitialPaymentLinks(List.of());
        verifyNoInteractions(links, policies, resolver);
        assertThat(DiscountLink.builder().build().isEnabled()).isTrue();
    }

    /** 검증 완료 정책 대역을 준비한다. */
    private void prepareLink() {
        when(links.findByCode("Crew")).thenReturn(Optional.of(link));
        when(policies.findById("policy")).thenReturn(Optional.of(DiscountPolicy.builder().eventId("event").build()));
        when(resolver.resolveLinkPolicy(link, "event", List.of(policy))).thenReturn(policy);
    }
}
