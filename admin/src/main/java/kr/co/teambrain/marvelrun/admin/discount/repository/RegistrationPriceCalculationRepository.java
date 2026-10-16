package kr.co.teambrain.marvelrun.admin.discount.repository;

import kr.co.teambrain.marvelrun.admin.discount.domain.RegistrationPriceCalculation;
import org.springframework.data.jpa.repository.JpaRepository;

/** 계산 기록을 신청 변경과 같은 트랜잭션에서 저장한다. */
public interface RegistrationPriceCalculationRepository extends JpaRepository<RegistrationPriceCalculation, String> {
}

