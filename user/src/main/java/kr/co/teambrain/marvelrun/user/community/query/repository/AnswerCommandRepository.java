package kr.co.teambrain.marvelrun.user.community.query.repository;

import kr.co.teambrain.marvelrun.user.community.command.application.domain.Answer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AnswerCommandRepository extends JpaRepository<Answer,String> {
    Optional<Answer> findByQuestionId(String questionId);
}
