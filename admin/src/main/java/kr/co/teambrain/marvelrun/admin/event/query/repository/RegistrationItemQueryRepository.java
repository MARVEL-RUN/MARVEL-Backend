package kr.co.teambrain.marvelrun.admin.event.query.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 신청 상세의 구매 줄을 읽는다. 상품 종목을 함께 가져온다. */
public interface RegistrationItemQueryRepository extends JpaRepository<RegistrationItem, String> {

    /** 신청의 구매 줄을 신청 안 순서로 돌려준다. */
    @EntityGraph(attributePaths = {"eventCategory"})
    List<RegistrationItem> findByRegistration_IdOrderBySortOrderAsc(String registrationId);
}
