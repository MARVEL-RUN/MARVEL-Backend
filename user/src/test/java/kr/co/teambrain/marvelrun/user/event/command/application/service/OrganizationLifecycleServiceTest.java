package kr.co.teambrain.marvelrun.user.event.command.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 단체 삭제가 참가 취소 요청이 아니라 환불 완료 이후에만 발생하는지 검증한다. */
class OrganizationLifecycleServiceTest {
    private final EntityManager em = mock(EntityManager.class);
    private final RegistrationCommandRepository registrations = mock(RegistrationCommandRepository.class);
    private final OrganizationLifecycleService service = new OrganizationLifecycleService(em, registrations);

    /** 환불 잔액이 남으면 계정과 자격 연결을 그대로 유지한다. */
    @Test
    void waitsForRefundCompletion() {
        Organization organization = Organization.builder().id("org").discountLinkId("link").referenceFilterId("filter").build();
        when(em.find(Organization.class,"org")).thenReturn(organization);
        when(registrations.findAllByOrganization_Id("org")).thenReturn(List.of(Registration.builder()
                .softDeleted(true).status(RegistrationStatus.CANCELLATION_PENDING).paidAmount(BigDecimal.TEN).build()));
        service.completeOrganizationCancellation("org");
        assertThat(organization.isSoftDeleted()).isFalse();
        verify(em,never()).createQuery(anyString(),eq(Long.class));
    }

    /** 마지막 환불 완료 시 계정만 비활성화하며 연결 데이터를 지우지 않는다. */
    @Test
    @SuppressWarnings("unchecked")
    void preservesQualificationAfterCompletedCancellation() {
        Organization organization = Organization.builder().id("org").discountLinkId("link").referenceFilterId("filter").build();
        when(em.find(Organization.class,"org")).thenReturn(organization);
        when(registrations.findAllByOrganization_Id("org")).thenReturn(List.of(Registration.builder()
                .softDeleted(true).status(RegistrationStatus.CANCELED).paidAmount(BigDecimal.ZERO).build()));
        TypedQuery<Long> query = mock(TypedQuery.class);
        when(em.createQuery(anyString(),eq(Long.class))).thenReturn(query);
        when(query.setParameter(anyString(),any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(0L);
        service.completeOrganizationCancellation("org");
        assertThat(organization.isSoftDeleted()).isTrue();
        assertThat(organization.getDiscountLinkId()).isEqualTo("link");
        assertThat(organization.getReferenceFilterId()).isEqualTo("filter");
    }
}
