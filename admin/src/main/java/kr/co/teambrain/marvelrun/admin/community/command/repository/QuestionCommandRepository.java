package kr.co.teambrain.marvelrun.admin.community.command.repository;

import jakarta.persistence.LockModeType;
import kr.co.teambrain.marvelrun.admin.community.command.application.domain.Question;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface QuestionCommandRepository
        extends JpaRepository<Question, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select q
            from Question q
            where q.id = :questionId
            """)
    Optional<Question> findByIdForUpdate(
            @Param("questionId")
            String questionId
    );
}