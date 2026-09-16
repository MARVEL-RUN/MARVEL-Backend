package kr.co.teambrain.marvelrun.admin.auth.command.repository;


import kr.co.teambrain.marvelrun.admin.auth.command.application.domain.Admin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AdminCommandRepository extends JpaRepository<Admin, String> {

    Optional<Admin> findByLoginId(String loginId);
}
