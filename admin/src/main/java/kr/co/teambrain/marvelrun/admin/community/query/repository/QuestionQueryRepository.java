package kr.co.teambrain.marvelrun.admin.community.query.repository;


import kr.co.teambrain.marvelrun.admin.community.command.application.domain.Question;
import kr.co.teambrain.marvelrun.admin.community.query.dto.projection.AdminQuestionAnswerProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface QuestionQueryRepository
        extends JpaRepository<Question, String> {


    /**
     * 전체 또는 특정 Event의 Question 검색.
     *
     * eventId == null
     * → 모든 Question
     *
     * eventId != null
     * → 특정 Event Question
     *
     * isAnswered == null
     * → 답변 여부 전체
     */
    @Query(
            value = """
                    select
                        q.id as questionId,
                        q.title as questionTitle,
                        q.authorName as authorName,
                        q.createdAt as questionCreatedAt,
                        q.isSecret as secret,
                        q.isAnswered as answered,
                        q.event.id as eventId,
                        a.id as answerId,
                        a.title as answerTitle,
                        adm.name as answerAuthorName,
                        a.createdAt as answerCreatedAt
                    from Question q
                    left join Answer a
                        on a.question = q
                    left join a.admin adm
                    where
                        (
                            :eventId is null
                            or q.event.id = :eventId
                        )
                    and
                        (
                            :isAnswered is null
                            or q.isAnswered = :isAnswered
                        )
                    and
                        (
                            :keyword = ''
                    
                            or
                    
                            (
                                :target = 'ALL'
                                and (
                                    q.title like concat(:keyword, '%')
                                    or q.authorName like concat(:keyword, '%')
                                )
                            )
                    
                            or
                    
                            (
                                :target = 'TITLE'
                                and q.title like concat(:keyword, '%')
                            )
                    
                            or
                    
                            (
                                :target = 'AUTHOR'
                                and q.authorName like concat(:keyword, '%')
                            )
                        )
                    order by q.createdAt desc, q.id desc
                    """,

            countQuery = """
                    select count(q)
                    from Question q
                    where
                        (
                            :eventId is null
                            or q.event.id = :eventId
                        )
                    and
                        (
                            :isAnswered is null
                            or q.isAnswered = :isAnswered
                        )
                    and
                        (
                            :keyword = ''
                    
                            or
                    
                            (
                                :target = 'ALL'
                                and (
                                    q.title like concat(:keyword, '%')
                                    or q.authorName like concat(:keyword, '%')
                                )
                            )
                    
                            or
                    
                            (
                                :target = 'TITLE'
                                and q.title like concat(:keyword, '%')
                            )
                    
                            or
                    
                            (
                                :target = 'AUTHOR'
                                and q.authorName like concat(:keyword, '%')
                            )
                        )
                    """
    )
    Page<AdminQuestionAnswerProjection> searchQuestions(
            @Param("eventId")
            String eventId,

            @Param("target")
            String target,

            @Param("keyword")
            String keyword,

            @Param("isAnswered")
            Boolean isAnswered,

            Pageable pageable
    );


    /**
     * event_id IS NULL인 홈페이지/일반 문의 전용.
     */
    @Query(
            value = """
                    select
                        q.id as questionId,
                        q.title as questionTitle,
                        q.authorName as authorName,
                        q.createdAt as questionCreatedAt,
                        q.isSecret as secret,
                        q.isAnswered as answered,
                        q.event.id as eventId,
                        a.id as answerId,
                        a.title as answerTitle,
                        adm.name as answerAuthorName,
                        a.createdAt as answerCreatedAt
                    from Question q
                    left join Answer a
                        on a.question = q
                    left join a.admin adm
                    where q.event is null
                    and
                        (
                            :isAnswered is null
                            or q.isAnswered = :isAnswered
                        )
                    and
                        (
                            :keyword = ''
                    
                            or
                    
                            (
                                :target = 'ALL'
                                and (
                                    q.title like concat(:keyword, '%')
                                    or q.authorName like concat(:keyword, '%')
                                )
                            )
                    
                            or
                    
                            (
                                :target = 'TITLE'
                                and q.title like concat(:keyword, '%')
                            )
                    
                            or
                    
                            (
                                :target = 'AUTHOR'
                                and q.authorName like concat(:keyword, '%')
                            )
                        )
                    order by q.createdAt desc, q.id desc
                    """,

            countQuery = """
                    select count(q)
                    from Question q
                    where q.event is null
                    and
                        (
                            :isAnswered is null
                            or q.isAnswered = :isAnswered
                        )
                    and
                        (
                            :keyword = ''
                    
                            or
                    
                            (
                                :target = 'ALL'
                                and (
                                    q.title like concat(:keyword, '%')
                                    or q.authorName like concat(:keyword, '%')
                                )
                            )
                    
                            or
                    
                            (
                                :target = 'TITLE'
                                and q.title like concat(:keyword, '%')
                            )
                    
                            or
                    
                            (
                                :target = 'AUTHOR'
                                and q.authorName like concat(:keyword, '%')
                            )
                        )
                    """
    )
    Page<AdminQuestionAnswerProjection> searchHomepageQuestions(
            @Param("target")
            String target,

            @Param("keyword")
            String keyword,

            @Param("isAnswered")
            Boolean isAnswered,

            Pageable pageable
    );
}