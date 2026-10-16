package kr.co.teambrain.marvelrun.user.discount.service;

import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.discount.domain.DiscountLink;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 금액 상태가 아닌 참가 자격 확보 상태로 최소 인원을 검증한다. */
class OrganizationDiscountGuardTest {
    /** 일반 인원과 추가결제·부분환불 인원을 포함하되 미결제 신규 대체는 거부한다. */
    @Test
    void countsConsumedMembersRegardlessOfLinkAndFinancialState() {
        DiscountPolicyResolver policies = mock(DiscountPolicyResolver.class);
        ReservationCommandRepository reservations = mock(ReservationCommandRepository.class);
        OrganizationDiscountGuard guard = new OrganizationDiscountGuard(policies,reservations);
        Organization organization = Organization.builder().id("org").event(Event.builder().id("event").build()).discountLinkId("link").build();
        DiscountLink link = DiscountLink.builder().id("link").build();
        DiscountModels.Policy policy = new DiscountModels.Policy("policy","event",1,DiscountModels.Stage.EXTERNAL,
                DiscountModels.Method.PERCENT,new BigDecimal("50"),null,DiscountModels.ApplicationType.ORGANIZATION,
                DiscountModels.CategoryScope.ALL,Set.of(),5,null,null);
        when(policies.resolveStoredLink("link")).thenReturn(link);
        when(policies.resolveEventPolicies("event")).thenReturn(List.of(policy));
        when(policies.resolveLinkPolicy(link,"event",List.of(policy))).thenReturn(policy);
        List<Registration> members = IntStream.range(0,5).<Registration>mapToObj(index -> Registration.builder().id("r"+index)
                .status(index == 0 ? RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED : RegistrationStatus.PARTIAL_REFUND_REQUIRED).build()).toList();
        when(reservations.findAllByRegistrationIds(members.stream().map(Registration::getId).toList()))
                .thenReturn(members.stream().<Reservation>map(member -> Reservation.builder().registration(member).status(ReservationStatus.CONSUMED).build()).toList());
        assertThatCode(() -> guard.validateOrganizationMemberChange(organization,members,Set.of("r0","r1","r2","r3","r4"),0))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.validateOrganizationMemberChange(organization,members,Set.of("r0","r1","r2","r3"),1))
                .isInstanceOf(CustomException.class);
        assertThatCode(() -> guard.validateOrganizationMemberChange(organization,members,Set.of(),0)).doesNotThrowAnyException();
    }
}
