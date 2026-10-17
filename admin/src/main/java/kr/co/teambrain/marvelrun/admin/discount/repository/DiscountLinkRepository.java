package kr.co.teambrain.marvelrun.admin.discount.repository;

import kr.co.teambrain.marvelrun.admin.discount.domain.DiscountLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 공개 코드 또는 저장된 ID로 링크 자격의 원본 설정을 조회한다. */
public interface DiscountLinkRepository extends JpaRepository<DiscountLink, String> {
    /** 공개 코드에 대응하는 링크를 조회하며 대회·기간 검증은 서비스에서 수행한다. */
    Optional<DiscountLink> findByCode(String code);
}
