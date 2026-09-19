package kr.co.teambrain.marvelrun.user.payment.command.application;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentAllocationCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentAllocationCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentAllocationTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * PaymentAllocationCreator가 Payment 금액 귀속의 핵심 불변식을
 * 저장 전에 검증하는지 확인한다.
 */
class PaymentAllocationCreatorTest {

    private PaymentAllocationCommandRepository repository;
    private PaymentAllocationCreator creator;

    /**
     * 각 테스트에서 독립적인 Repository Mock과 Creator를 구성한다.
     */
    @BeforeEach
    void setUp() {

        repository =
                mock(
                        PaymentAllocationCommandRepository.class
                );

        creator =
                new PaymentAllocationCreator(
                        repository
                );

        when(
                repository.saveAll(
                        anyList()
                )
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(0)
        );
    }

    /**
     * 단체 Payment의 금액과 각 Registration 귀속금액 합계가 일치하면
     * Allocation 전체를 생성하는지 검증한다.
     */
    @Test
    void createsGroupAllocationsWhenTotalMatches() {

        Organization organization =
                organization("org-1");

        Registration first =
                registration(
                        "reg-1",
                        organization
                );

        Registration second =
                registration(
                        "reg-2",
                        organization
                );

        Payment payment =
                groupPayment(
                        "payment-1",
                        organization,
                        "110000"
                );

        List<PaymentAllocation> result =
                creator.create(
                        payment,
                        List.of(
                                new PaymentAllocationTarget(
                                        first,
                                        new BigDecimal("70000")
                                ),
                                new PaymentAllocationTarget(
                                        second,
                                        new BigDecimal("40000")
                                )
                        )
                );

        assertThat(result)
                .hasSize(2);

        assertThat(
                result.stream()
                        .map(
                                PaymentAllocation::getAllocatedAmount
                        )
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        )
        ).isEqualByComparingTo("110000");

        verify(repository)
                .saveAll(anyList());
    }

    /**
     * Allocation 합계가 Payment.amount와 다르면
     * DB 저장 이전에 차단하는지 검증한다.
     */
    @Test
    void rejectsAmountMismatch() {

        Organization organization =
                organization("org-1");

        Registration registration =
                registration(
                        "reg-1",
                        organization
                );

        Payment payment =
                groupPayment(
                        "payment-1",
                        organization,
                        "50000"
                );

        assertThatThrownBy(
                () ->
                        creator.create(
                                payment,
                                List.of(
                                        new PaymentAllocationTarget(
                                                registration,
                                                new BigDecimal("40000")
                                        )
                                )
                        )
        ).isInstanceOf(
                CustomException.class
        );

        verify(
                repository,
                never()
        ).saveAll(anyList());
    }

    /**
     * 한 Payment에서 동일 Registration을 두 번 귀속시키는 것을
     * 저장 전에 차단하는지 검증한다.
     */
    @Test
    void rejectsDuplicateRegistration() {

        Organization organization =
                organization("org-1");

        Registration registration =
                registration(
                        "reg-1",
                        organization
                );

        Payment payment =
                groupPayment(
                        "payment-1",
                        organization,
                        "80000"
                );

        assertThatThrownBy(
                () ->
                        creator.create(
                                payment,
                                List.of(
                                        new PaymentAllocationTarget(
                                                registration,
                                                new BigDecimal("40000")
                                        ),
                                        new PaymentAllocationTarget(
                                                registration,
                                                new BigDecimal("40000")
                                        )
                                )
                        )
        ).isInstanceOf(
                CustomException.class
        );

        verify(
                repository,
                never()
        ).saveAll(anyList());
    }

    /**
     * 단체 Payment에 다른 Organization의 Registration이 포함되면
     * 저장 전에 차단하는지 검증한다.
     */
    @Test
    void rejectsRegistrationFromOtherOrganization() {

        Organization paymentOrganization =
                organization("org-1");

        Organization otherOrganization =
                organization("org-2");

        Registration registration =
                registration(
                        "reg-1",
                        otherOrganization
                );

        Payment payment =
                groupPayment(
                        "payment-1",
                        paymentOrganization,
                        "40000"
                );

        assertThatThrownBy(
                () ->
                        creator.create(
                                payment,
                                List.of(
                                        new PaymentAllocationTarget(
                                                registration,
                                                new BigDecimal("40000")
                                        )
                                )
                        )
        ).isInstanceOf(
                CustomException.class
        );

        verify(
                repository,
                never()
        ).saveAll(anyList());
    }

    /**
     * 개인 Payment에서는 직접 대상 Registration과 동일한 Registration에
     * Payment.amount 전체가 Allocation 1건으로 귀속되는지 검증한다.
     */
    @Test
    void createsSingleAllocationForRegistrationPayment() {

        Registration registration =
                mock(Registration.class);

        when(registration.getId())
                .thenReturn("reg-1");

        Payment payment =
                mock(Payment.class);

        when(payment.getId())
                .thenReturn("payment-1");

        when(payment.getRegistration())
                .thenReturn(registration);

        when(payment.getAmount())
                .thenReturn(
                        new BigDecimal("40000")
                );

        when(payment.isRegistrationPayment())
                .thenReturn(true);

        when(payment.isOrgPayment())
                .thenReturn(false);

        List<PaymentAllocation> result =
                creator.create(
                        payment,
                        List.of(
                                new PaymentAllocationTarget(
                                        registration,
                                        new BigDecimal("40000")
                                )
                        )
                );

        assertThat(result)
                .hasSize(1);

        assertThat(
                result.get(0)
                        .getRegistration()
        ).isSameAs(registration);

        assertThat(
                result.get(0)
                        .getAllocatedAmount()
        ).isEqualByComparingTo("40000");
    }

    /**
     * 테스트용 Organization Mock을 생성한다.
     */
    private Organization organization(
            String id
    ) {

        Organization organization =
                mock(Organization.class);

        when(
                organization.getId()
        ).thenReturn(id);

        return organization;
    }

    /**
     * 테스트용 Registration Mock을 생성한다.
     */
    private Registration registration(
            String id,
            Organization organization
    ) {

        Registration registration =
                mock(Registration.class);

        when(
                registration.getId()
        ).thenReturn(id);

        when(
                registration.getOrganization()
        ).thenReturn(organization);

        return registration;
    }

    /**
     * 테스트용 Organization 대상 Payment Mock을 생성한다.
     */
    private Payment groupPayment(
            String id,
            Organization organization,
            String amount
    ) {

        Payment payment =
                mock(Payment.class);

        when(
                payment.getId()
        ).thenReturn(id);

        when(
                payment.getOrganization()
        ).thenReturn(organization);

        when(
                payment.getAmount()
        ).thenReturn(
                new BigDecimal(amount)
        );

        when(
                payment.isRegistrationPayment()
        ).thenReturn(false);

        when(
                payment.isOrgPayment()
        ).thenReturn(true);

        return payment;
    }
}