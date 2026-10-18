package kr.co.teambrain.marvelrun.user.discount.service;

import jakarta.persistence.EntityManager;
import kr.co.teambrain.marvelrun.common.discount.DiscountPriceCalculator;
import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import kr.co.teambrain.marvelrun.common.discount.model.RegistrationPriceCalculationSnapshot;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.discount.domain.RegistrationPriceCalculation;
import kr.co.teambrain.marvelrun.user.discount.repository.RegistrationPriceCalculationRepository;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** 저장 순서와 실패 검증을 확인한다. 실제 롤백은 DB 회귀 테스트에서 검증한다. */
class RegistrationPriceCalculationRecorderTest {
    private final RegistrationPriceCalculationRepository calculations = mock(RegistrationPriceCalculationRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final RegistrationPriceCalculationRecorder recorder = new RegistrationPriceCalculationRecorder(calculations,
        entityManager);

    /** 할인 없는 계산도 빈 적용 목록으로 기록하고 기존 참조는 저장 후 교체한다. */
    @Test
    void recordsUndiscountedPriceBeforeReplacingCurrentReference() {
        Registration registration = Registration.builder().id("registration")
            .contractAmount(new BigDecimal("70000")).currentPriceCalculationId("previous").build();
        DiscountModels.PriceResult price = new DiscountPriceCalculator()
            .calculateDiscountedPrice(new BigDecimal("70000"), null, null);
        when(entityManager.contains(registration)).thenReturn(true);
        when(calculations.saveAllAndFlush(anyList())).thenAnswer(invocation -> {
            List<RegistrationPriceCalculation> records = invocation.getArgument(0);
            RegistrationPriceCalculation record = records.get(0);
            assertThat(registration.getCurrentPriceCalculationId()).isEqualTo("previous");
            assertThat(record.getRegistrationId()).isEqualTo("registration");
            assertThat(record.getBaseAmount()).isEqualByComparingTo("70000");
            assertThat(record.getFinalAmount()).isEqualByComparingTo("70000");
            assertThat(record.getSnapshot().schemaVersion()).isEqualTo(1);
            assertThat(record.getSnapshot().appliedDiscounts()).isEmpty();
            assertThat(record.getCalculationReason()).isEqualTo(RegistrationPriceCalculationSnapshot.Reason.REGISTRATION_MODIFIED);
            return List.of(RegistrationPriceCalculation.builder().id("new-calculation")
                .registrationId(record.getRegistrationId()).build());
        });

        recorder.recordRegistrationPriceCalculation(registration, price,
            RegistrationPriceCalculationSnapshot.Reason.REGISTRATION_MODIFIED);

        assertThat(registration.getCurrentPriceCalculationId()).isEqualTo("new-calculation");
        InOrder order = inOrder(entityManager, calculations);
        order.verify(entityManager).contains(registration);
        order.verify(entityManager).flush();
        order.verify(calculations).saveAllAndFlush(anyList());
    }

    /** 계약금액과 계산이 다르면 참조와 저장소 모두 변경하지 않는다. */
    @Test
    void rejectsPriceThatWasNotAppliedToRegistration() {
        Registration registration = Registration.builder().id("registration")
            .contractAmount(BigDecimal.TEN).currentPriceCalculationId("previous").build();
        when(entityManager.contains(registration)).thenReturn(true);
        DiscountModels.PriceResult price = new DiscountPriceCalculator()
            .calculateDiscountedPrice(new BigDecimal("70000"), null, null);

        assertThatThrownBy(() -> recorder.recordRegistrationPriceCalculation(registration, price,
            RegistrationPriceCalculationSnapshot.Reason.REGISTRATION_MODIFIED)).isInstanceOf(CustomException.class);

        assertThat(registration.getCurrentPriceCalculationId()).isEqualTo("previous");
        verifyNoInteractions(calculations);
        verify(entityManager, never()).flush();
    }

    /** 중복 신청이 포함되면 일괄 저장 전체를 기록 전에 거부한다. */
    @Test
    void rejectsDuplicateRegistrationBeforeWriting() {
        Registration registration = Registration.builder().id("registration").contractAmount(BigDecimal.TEN).build();
        when(entityManager.contains(registration)).thenReturn(true);
        DiscountModels.PriceResult price = new DiscountPriceCalculator().calculateDiscountedPrice(BigDecimal.TEN, null, null);
        RegistrationPriceCalculationRecorder.Input input = new RegistrationPriceCalculationRecorder.Input(registration, price);
        assertThatThrownBy(() -> recorder.recordRegistrationPriceCalculations(List.of(input, input),
            RegistrationPriceCalculationSnapshot.Reason.REGISTRATION_CREATED)).isInstanceOf(CustomException.class);
        verifyNoInteractions(calculations);
        verify(entityManager, never()).flush();
    }
}
