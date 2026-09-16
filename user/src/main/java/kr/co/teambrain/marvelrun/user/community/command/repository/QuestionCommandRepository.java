package kr.co.teambrain.marvelrun.user.community.command.repository;

import kr.co.teambrain.marvelrun.user.community.command.application.domain.Question;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QuestionCommandRepository extends JpaRepository<Question, String> {
    List<Question> findAllByUserId(String id);
}
