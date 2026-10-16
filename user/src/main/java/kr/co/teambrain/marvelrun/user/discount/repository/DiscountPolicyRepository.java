package kr.co.teambrain.marvelrun.user.discount.repository;

import kr.co.teambrain.marvelrun.user.discount.domain.DiscountPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;


/** 할인 구성 저장소이며 관리용 쓰기 API는 제공하지 않는다. */
public interface DiscountPolicyRepository extends JpaRepository<DiscountPolicy, String> {
    List<DiscountPolicy> findByEventId(String eventId);
}