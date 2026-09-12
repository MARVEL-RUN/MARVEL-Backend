package kr.co.teambrain.marvelrun.user.userinfo.command.Repository;

import kr.co.teambrain.marvelrun.user.userinfo.command.application.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserCommandRepository extends JpaRepository<User,String> {

    Optional<User> findByAccount(String accountId);
}
