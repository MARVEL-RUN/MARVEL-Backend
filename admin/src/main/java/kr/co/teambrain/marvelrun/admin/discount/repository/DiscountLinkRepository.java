package kr.co.teambrain.marvelrun.admin.discount.repository;

import kr.co.teambrain.marvelrun.admin.discount.domain.DiscountLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;


/** 할인 구성 저장소이며 관리용 쓰기 API는 제공하지 않는다. */
public interface DiscountLinkRepository extends JpaRepository<DiscountLink, String> {
    Optional<DiscountLink> findByCode(String code);
}