package kr.co.teambrain.marvelrun.admin.discount.repository;

import kr.co.teambrain.marvelrun.admin.discount.domain.InternalDiscountCondition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Collection;

/** 서비스 내부 정책의 조건 행을 조회한다. 현재는 연령 범위를 지원한다. */
public interface InternalDiscountConditionRepository extends JpaRepository<InternalDiscountCondition, String> {
    /** 여러 정책에 연결된 내부 조건을 한 번에 조회한다. */
    List<InternalDiscountCondition> findByPolicyIdIn(Collection<String> policyIds);
}
