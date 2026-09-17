package kr.co.teambrain.marvelrun.admin.community.command.repository;

import kr.co.teambrain.marvelrun.admin.community.command.application.domain.Notice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NoticeCommandRepository extends JpaRepository<Notice, String> {
}
