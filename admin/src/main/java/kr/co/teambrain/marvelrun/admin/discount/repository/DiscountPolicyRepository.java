package kr.co.teambrain.marvelrun.admin.discount.repository;

import kr.co.teambrain.marvelrun.admin.discount.domain.DiscountPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 대회별 할인 정책을 일괄 조회한다. 정책 설정 변경 API는 이 저장소의 책임이 아니다. */
public interface DiscountPolicyRepository extends JpaRepository<DiscountPolicy, String> {
    /** 대회에 속한 내부·외부 정책을 함께 조회한다. */
    List<DiscountPolicy> findByEventId(String eventId);
}
