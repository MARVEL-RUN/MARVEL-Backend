package kr.co.teambrain.marvelrun.admin.discount.repository;

import kr.co.teambrain.marvelrun.admin.discount.domain.DiscountAgeCondition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Collection;

/** 할인 구성 저장소이며 관리용 쓰기 API는 제공하지 않는다. */
public interface DiscountAgeConditionRepository extends JpaRepository<DiscountAgeCondition, String> {
    List<DiscountAgeCondition> findByPolicyIdIn(Collection<String> policyIds);
}