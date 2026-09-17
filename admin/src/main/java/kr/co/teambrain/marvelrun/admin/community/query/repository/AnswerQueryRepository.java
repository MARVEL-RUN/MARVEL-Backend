package kr.co.teambrain.marvelrun.admin.community.query.repository;

import kr.co.teambrain.marvelrun.admin.community.command.application.domain.Answer;
import kr.co.teambrain.marvelrun.admin.community.query.dto.AdminAnswerDetailProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AnswerQueryRepository
        extends JpaRepository<Answer, String> {


    @Query("""
            select
                a.id as id,
                a.title as title,
                a.content as content,
                adm.name as author,
                a.createdAt as createdAt
            from Answer a
            join a.admin adm
            where a.id = :answerId
            """)
    Optional<AdminAnswerDetailProjection> findDetailById(
            @Param("answerId")
            String answerId
    );


    @Query("""
            select
                a.id as id,
                a.title as title,
                a.content as content,
                adm.name as author,
                a.createdAt as createdAt
            from Answer a
            join a.admin adm
            where a.question.id = :questionId
            """)
    Optional<AdminAnswerDetailProjection> findDetailByQuestionId(
            @Param("questionId")
            String questionId
    );
}