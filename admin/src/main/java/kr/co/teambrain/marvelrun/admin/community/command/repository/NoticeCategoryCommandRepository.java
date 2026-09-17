package kr.co.teambrain.marvelrun.admin.community.command.repository;

import kr.co.teambrain.marvelrun.admin.community.command.application.domain.NoticeCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NoticeCategoryCommandRepository extends JpaRepository<NoticeCategory, String> {
}
