package kr.co.teambrain.marvelrun.user.discount.repository;

import kr.co.teambrain.marvelrun.user.discount.domain.DiscountPolicyCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Collection;

/** 정책의 선택 종목 연결을 조회한다. */
public interface DiscountPolicyCategoryRepository extends JpaRepository<DiscountPolicyCategory, String> {
    /** 여러 정책의 종목 연결을 한 번에 조회해 참가자별 반복 조회를 피한다. */
    List<DiscountPolicyCategory> findByPolicyIdIn(Collection<String> policyIds);
}
