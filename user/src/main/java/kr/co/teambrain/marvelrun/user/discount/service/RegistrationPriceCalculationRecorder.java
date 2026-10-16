package kr.co.teambrain.marvelrun.user.discount.service;

import jakarta.persistence.EntityManager;
import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import kr.co.teambrain.marvelrun.common.discount.model.RegistrationPriceCalculationSnapshot;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.discount.domain.RegistrationPriceCalculation;
import kr.co.teambrain.marvelrun.user.discount.repository.RegistrationPriceCalculationRepository;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 계산 근거와 현재 참조를 기존 신청 트랜잭션 안에서 함께 저장한다. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RegistrationPriceCalculationRecorder {
    private final RegistrationPriceCalculationRepository calculations;
    private final EntityManager entityManager;

    /** 저장할 신청과 이미 판정이 끝난 계산 결과를 대응시킨다. */
    public record Input(Registration registration, DiscountModels.PriceResult price) { }

    /** 개인 신청 한 건도 단체와 동일한 저장·소유 검증을 거친다. */
    public void recordRegistrationPriceCalculation(Registration registration, DiscountModels.PriceResult price,
            RegistrationPriceCalculationSnapshot.Reason reason) {
        recordRegistrationPriceCalculations(List.of(new Input(registration, price)), reason);
    }

    /** 단체 참가자별 flush를 피하고 신청 INSERT, 계산 INSERT, 현재 참조 UPDATE 순서를 보장한다. */
    public void recordRegistrationPriceCalculations(List<Input> inputs, RegistrationPriceCalculationSnapshot.Reason reason) {
        if (inputs.isEmpty()) {
            return;
        }

        // 중복 신청·미저장 엔티티·실패 계산은 일부 기록을 남기기 전에 차단한다.
        Set<String> ids = new HashSet<>();
        List<RegistrationPriceCalculation> records = new ArrayList<>();
        for (Input input : inputs) {
            Registration registration = input.registration();
            DiscountModels.PriceResult price = input.price();
            if (reason == null || registration == null || registration.getId() == null
                    || !entityManager.contains(registration) || !ids.add(registration.getId())
                    || price == null || price.failure() != DiscountModels.Failure.NONE
                    || price.baseAmount() == null || price.baseAmount().signum() < 0
                    || price.finalAmount() == null || price.finalAmount().signum() <= 0
                    || price.roundingAdjustmentAmount() == null || price.appliedDiscounts() == null
                    || registration.getContractAmount() == null
                    || registration.getContractAmount().compareTo(price.finalAmount()) != 0) {
                throw new CustomException(ErrorCode.DISCOUNT_CONFIGURATION_ERROR);
            }
            records.add(RegistrationPriceCalculation.builder()
                    .registrationId(registration.getId())
                    .baseAmount(price.baseAmount())
                    .finalAmount(price.finalAmount())
                    .roundingAdjustmentAmount(price.roundingAdjustmentAmount())
                    .calculationReason(reason)
                    .snapshot(new RegistrationPriceCalculationSnapshot(1, price.appliedDiscounts()))
                    .build());
        }

        // 신규 신청 FK를 먼저 만족시키고 계산 행이 존재한 뒤 현재 참조를 연결한다.
        entityManager.flush();
        List<RegistrationPriceCalculation> saved = calculations.saveAllAndFlush(records);
        for (int index = 0; index < inputs.size(); index++) {
            Registration registration = inputs.get(index).registration();
            RegistrationPriceCalculation calculation = saved.get(index);
            if (calculation.getId() == null || !registration.getId().equals(calculation.getRegistrationId())) {
                throw new CustomException(ErrorCode.DISCOUNT_CONFIGURATION_ERROR);
            }
            registration.assignCurrentPriceCalculation(calculation.getId());
        }
    }
}

