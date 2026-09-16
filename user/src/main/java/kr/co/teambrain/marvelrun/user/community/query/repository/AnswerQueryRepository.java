package kr.co.teambrain.marvelrun.user.community.query.repository;

import io.lettuce.core.Value;
import kr.co.teambrain.marvelrun.user.community.command.application.domain.Answer;
import kr.co.teambrain.marvelrun.user.community.command.application.domain.Question;
import kr.co.teambrain.marvelrun.user.community.query.dto.AnswerDetail;
import kr.co.teambrain.marvelrun.user.community.query.dto.AnswerHeaderProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AnswerQueryRepository
        extends JpaRepository<Answer, String> {


    /**
     * 현재 페이지의 Question들에 연결된 Answer Header를
     * 한 번에 가져온다.
     */
    @Query("""
            select new kr.co.teambrain.marvelrun.user.community.query.dto.AnswerHeaderProjection(
                    q.id,
                    a.id,
                    a.title,
                    admin.name,
                    a.createdAt
            )
            from Answer a
            join a.question q
            join a.admin admin
            where q.id in :questionIds
            """)
    List<AnswerHeaderProjection> findHeadersByQuestionIds(
            @Param("questionIds")
            List<String> questionIds
    );


    /**
     * Answer 상세 조회.
     *
     * 비밀글 여부와 password는 연결된 Question의 정책을 사용한다.
     */
    @Query("""
            select new kr.co.teambrain.marvelrun.user.community.query.dto.AnswerDetail(
                    a.id,
                    a.title,
                    a.content,
                    admin.name,
                    a.createdAt,
                    q.isSecret,
                    q.password
            )
            from Answer a
            join a.admin admin
            join a.question q
            where a.id = :answerId
            """)
    Optional<AnswerDetail> findAnswerDetailById(
            @Param("answerId")
            String answerId
    );

    Optional<Answer> findByQuestion(Question question);
}