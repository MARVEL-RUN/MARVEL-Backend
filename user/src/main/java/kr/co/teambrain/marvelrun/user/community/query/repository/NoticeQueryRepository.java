package kr.co.teambrain.marvelrun.user.community.query.repository;

import kr.co.teambrain.marvelrun.user.community.command.application.domain.Notice;
import kr.co.teambrain.marvelrun.user.community.query.dto.NoticeListResponse;
import kr.co.teambrain.marvelrun.user.community.query.dto.PinnedNoticeResponse;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NoticeQueryRepository
        extends JpaRepository<Notice, String> {


    @Query(
            value = """
                    SELECT new kr.co.teambrain.marvelrun.user.community.query.dto.NoticeListResponse(
                        n.id,
                        n.title,
                        n.category.name,
                        n.createdAt,
                        n.admin.name,
                        n.viewCount
                    )
                    FROM Notice n
                    WHERE
                        (
                            (:eventId IS NULL AND n.event IS NULL)
                            OR
                            (:eventId IS NOT NULL AND n.event.id = :eventId)
                        )
                    
                        AND
                        (
                            :keyword IS NULL
                            OR TRIM(:keyword) = ''
                    
                            OR (
                                :target = 'ALL'
                                AND (
                                    n.title LIKE CONCAT('%', :keyword, '%')
                                    OR n.content LIKE CONCAT('%', :keyword, '%')
                                    OR n.admin.name LIKE CONCAT('%', :keyword, '%')
                                )
                            )
                    
                            OR (
                                :target = 'TITLE'
                                AND n.title LIKE CONCAT('%', :keyword, '%')
                            )
                    
                            OR (
                                :target = 'CONTENT'
                                AND n.content LIKE CONCAT('%', :keyword, '%')
                            )
                    
                            OR (
                                :target = 'AUTHOR'
                                AND n.admin.name LIKE CONCAT('%', :keyword, '%')
                            )
                        )
                    """,

            countQuery = """
                    SELECT COUNT(n)
                    FROM Notice n
                    WHERE
                        (
                            (:eventId IS NULL AND n.event IS NULL)
                            OR
                            (:eventId IS NOT NULL AND n.event.id = :eventId)
                        )
                    
                        AND
                        (
                            :keyword IS NULL
                            OR TRIM(:keyword) = ''
                    
                            OR (
                                :target = 'ALL'
                                AND (
                                    n.title LIKE CONCAT('%', :keyword, '%')
                                    OR n.content LIKE CONCAT('%', :keyword, '%')
                                    OR n.admin.name LIKE CONCAT('%', :keyword, '%')
                                )
                            )
                    
                            OR (
                                :target = 'TITLE'
                                AND n.title LIKE CONCAT('%', :keyword, '%')
                            )
                    
                            OR (
                                :target = 'CONTENT'
                                AND n.content LIKE CONCAT('%', :keyword, '%')
                            )
                    
                            OR (
                                :target = 'AUTHOR'
                                AND n.admin.name LIKE CONCAT('%', :keyword, '%')
                            )
                        )
                    """
    )
    Page<NoticeListResponse> search(

            @Param("eventId")
            String eventId,

            @Param("target")
            String target,

            @Param("keyword")
            String keyword,

            Pageable pageable
    );


    @Query("""
            SELECT new kr.co.teambrain.marvelrun.user.community.query.dto.PinnedNoticeResponse(
                n.id,
                n.title,
                n.category.name,
                n.createdAt,
                n.admin.name,
                n.viewCount
            )
            FROM Notice n
            WHERE
                (
                    (:eventId IS NULL AND n.event IS NULL)
                    OR
                    (:eventId IS NOT NULL AND n.event.id = :eventId)
                )
                AND n.category.name = '필독'
            ORDER BY
                n.createdAt DESC,
                n.id DESC
            """)
    List<PinnedNoticeResponse> findPinnedNotices(

            @Param("eventId")
            String eventId,

            Limit limit
    );
}