package kr.co.teambrain.marvelrun.user.community.query.repository;

import kr.co.teambrain.marvelrun.user.community.command.application.domain.Question;
import kr.co.teambrain.marvelrun.user.community.query.dto.QuestionProjection;
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
     * Question 목록 + 검색.
     *
     * eventId == null
     * → 일반 문의
     *
     * eventId != null
     * → 해당 Event 문의
     *
     * keyword == ""
     * → 검색 조건 없이 전체 목록
     *
     * 정렬:
     * createdAt DESC
     * id DESC
     */
    @Query(
            value = """
                    select
                        q.id as id,
                        q.title as title,
                        q.authorName as authorName,
                        q.createdAt as createdAt,
                        q.isSecret as isSecret,
                        q.isAnswered as isAnswered
                    from Question q
                    where (
                            (:eventId is null and q.event is null)
                            or
                            (:eventId is not null and q.event.id = :eventId)
                    )
                    and (
                            :keyword = ''
                    
                            or
                    
                            (
                                :target = 'ALL'
                                and (
                                    q.title like concat(:keyword, '%')
                                    or
                                    q.authorName like concat(:keyword, '%')
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
                    """,

            countQuery = """
                    select count(q)
                    from Question q
                    where (
                            (:eventId is null and q.event is null)
                            or
                            (:eventId is not null and q.event.id = :eventId)
                    )
                    and (
                            :keyword = ''
                    
                            or
                    
                            (
                                :target = 'ALL'
                                and (
                                    q.title like concat(:keyword, '%')
                                    or
                                    q.authorName like concat(:keyword, '%')
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
    Page<QuestionProjection> search(
            @Param("eventId")
            String eventId,

            @Param("target")
            String target,

            @Param("keyword")
            String keyword,

            Pageable pageable
    );
}