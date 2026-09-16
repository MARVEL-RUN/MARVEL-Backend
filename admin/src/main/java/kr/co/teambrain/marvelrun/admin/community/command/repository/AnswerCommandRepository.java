package kr.co.teambrain.marvelrun.admin.community.command.repository;


import kr.co.teambrain.marvelrun.admin.community.command.application.domain.Answer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AnswerCommandRepository
        extends JpaRepository<Answer, String> {

    Optional<Answer> findByQuestion_Id(
            String questionId
    );


    @Query("""
            select a.question.id
            from Answer a
            where a.id = :answerId
            """)
    Optional<String> findQuestionIdByAnswerId(
            @Param("answerId")
            String answerId
    );
}