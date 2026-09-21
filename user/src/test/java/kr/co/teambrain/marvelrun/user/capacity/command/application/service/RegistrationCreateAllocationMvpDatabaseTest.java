package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentAllocationCreator;

import org.springframework.test.util.AopTestUtils;

/**
 * 실제 신청 생성 Transaction에서 Registration, Capacity, Reservation,
 * Payment와 PaymentAllocation이 함께 생성·롤백되는지 검증한다.
 */
class RegistrationCreateAllocationMvpDatabaseTest
        extends CapacityMvpTestSupport {

    /**
     * 개인 최초 신청은 Payment 한 건과 동일 Registration을 가리키는
     * PaymentAllocation 한 건을 함께 생성해야 한다.
     */
    @Test
    void personalApplicationCreatesSingleAllocation() {

        var result =
                personal(
                        categoryA,
                        "S",
                        "1990-01-01"
                );

        BigDecimal contractAmount =
                jdbc.queryForObject(
                        """
                        select contract_amount
                        from registration
                        where id = ?
                        """,
                        BigDecimal.class,
                        result.registrationId()
                );

        BigDecimal paymentAmount =
                jdbc.queryForObject(
                        """
                        select amount
                        from payment
                        where id = ?
                        """,
                        BigDecimal.class,
                        result.paymentId()
                );

        assertThat(contractAmount)
                .isEqualByComparingTo("40000");

        assertThat(paymentAmount)
                .isEqualByComparingTo(contractAmount);

        assertThat(
                allocationCount(
                        result.paymentId()
                )
        ).isEqualTo(1);

        assertThat(
                allocationAmount(
                        result.paymentId(),
                        result.registrationId()
                )
        ).isEqualByComparingTo(contractAmount);

        assertThat(
                allocationSum(
                        result.paymentId()
                )
        ).isEqualByComparingTo(paymentAmount);

        reservation(
                result.registrationId(),
                "HELD",
                1,
                1
        );
    }


    /**
     * 단체 최초 Payment는 구성원 수만큼 Allocation을 만들고,
     * 각 Registration.contractAmount와 동일한 금액을 귀속해야 한다.
     */
    @Test
    void groupApplicationCreatesAllocationForEveryRegistration() {

        var result =
                group(
                        categoryA,
                        categoryB
                );

        assertThat(result.registrationIds())
                .hasSize(2);

        assertThat(
                allocationCount(
                        result.paymentId()
                )
        ).isEqualTo(2);

        for (
                String registrationId
                : result.registrationIds()
        ) {

            BigDecimal contractAmount =
                    jdbc.queryForObject(
                            """
                            select contract_amount
                            from registration
                            where id = ?
                            """,
                            BigDecimal.class,
                            registrationId
                    );

            assertThat(
                    allocationAmount(
                            result.paymentId(),
                            registrationId
                    )
            ).isEqualByComparingTo(
                    contractAmount
            );
        }

        BigDecimal paymentAmount =
                jdbc.queryForObject(
                        """
                        select amount
                        from payment
                        where id = ?
                        """,
                        BigDecimal.class,
                        result.paymentId()
                );

        assertThat(
                allocationSum(
                        result.paymentId()
                )
        ).isEqualByComparingTo(
                paymentAmount
        );

        assertThat(paymentAmount)
                .isEqualByComparingTo("80000");
    }


    /**
     * 단체 구성원 중 하나라도 Capacity를 확보하지 못하면
     * Registration, Reservation, Payment, Allocation을 남기지 않고
     * 전체 신청 Transaction을 롤백해야 한다.
     */
    @Test
    void capacityFailureDoesNotLeavePaymentOrAllocation() {

        limit(
                categoryBCapacity,
                0
        );

        expectError(
                ErrorCode.CAPACITY_ACQUIRE_FAILED,
                () ->
                        group(
                                categoryA,
                                categoryB
                        )
        );

        noApplications();

        assertThat(n(
                """
                select count(*)
                from payment
                where registration_id in (
                    select id
                    from registration
                    where event_id = ?
                )
                or organization_id in (
                    select id
                    from organization
                    where event_id = ?
                )
                """,
                eventId,
                eventId
        )).isZero();

        counters(
                total,
                0,
                0
        );

        counters(
                categoryACapacity,
                0,
                0
        );

        counters(
                categoryBCapacity,
                0,
                0
        );

        counters(
                shirtS,
                0,
                0
        );
    }


    /**
     * Payment 생성 이후 Allocation 저장 단계에서 실패하더라도
     * 앞서 저장한 신청, 예약, Capacity 확보와 Payment까지
     * 모두 같은 Transaction에서 롤백되는지 검증한다.
     */
    @Test
    void allocationFailureRollsBackWholeGroupApplication() {

        /*
         * PaymentAllocationCreator는 @Transactional(MANDATORY) 프록시이므로
         * Spring Proxy 자체를 Mockito로 stubbing하면 stubbing 시점에
         * TransactionInterceptor가 먼저 실행된다.
         *
         * 실제 Spy target을 꺼내어 stubbing함으로써
         * 신청 Transaction 내부에서 Creator가 호출될 때만
         * 의도한 저장 실패를 발생시킨다.
         */
        PaymentAllocationCreator allocationCreatorTarget =
                AopTestUtils.getUltimateTargetObject(
                        paymentAllocationCreator
                );

        doThrow(
                new DataIntegrityViolationException(
                        "테스트용 PaymentAllocation 저장 실패"
                )
        ).when(
                allocationCreatorTarget
        ).create(
                any(),
                anyList()
        );

        assertThatThrownBy(
                () ->
                        group(
                                categoryA,
                                categoryB
                        )
        ).isInstanceOf(
                DataIntegrityViolationException.class
        );

        noApplications();

        counters(
                total,
                0,
                0
        );

        counters(
                categoryACapacity,
                0,
                0
        );

        counters(
                categoryBCapacity,
                0,
                0
        );

        counters(
                shirtS,
                0,
                0
        );
    }
}