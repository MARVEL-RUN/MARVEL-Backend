package kr.co.teambrain.marvelrun.admin.community.query.repository;



import kr.co.teambrain.marvelrun.admin.community.command.application.domain.NoticeCategory;
import kr.co.teambrain.marvelrun.admin.community.query.dto.response.NoticeCategoryResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NoticeCategoryQueryRepository extends JpaRepository<NoticeCategory, String> {

    public List<NoticeCategoryResponse> findAllBy();
}
