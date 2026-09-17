package kr.co.teambrain.marvelrun.admin.community.query.service;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;

import kr.co.teambrain.marvelrun.admin.community.command.application.domain.Notice;
import kr.co.teambrain.marvelrun.admin.community.query.domain.NoticeSearchTarget;
import kr.co.teambrain.marvelrun.admin.community.query.dto.response.NoticeCategoryResponse;
import kr.co.teambrain.marvelrun.admin.community.query.dto.response.NoticeDetailResponse;
import kr.co.teambrain.marvelrun.admin.community.query.dto.response.NoticeListResponse;
import kr.co.teambrain.marvelrun.admin.community.query.repository.NoticeCategoryQueryRepository;
import kr.co.teambrain.marvelrun.admin.community.query.repository.NoticeQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
public class NoticeQueryService {

    private final NoticeQueryRepository noticeQueryRepository;
    private final NoticeCategoryQueryRepository noticeCategoryQueryRepository;

    @Transactional(readOnly = true)
    public Page<NoticeListResponse> getNoticePage(
            String eventId,
            NoticeSearchTarget target,
            String keyword,
            Pageable pageable
    ) {

        NoticeSearchTarget normalizedTarget =
                target == null
                        ? NoticeSearchTarget.ALL
                        : target;


        String normalizedKeyword =
                keyword == null
                        ? ""
                        : keyword.strip();

        Page<NoticeListResponse> page = noticeQueryRepository.search(
                normalizeEventId(eventId),
                normalizedTarget.name(),
                normalizedKeyword,
                pageable
        );

        /*
         * 게시글 번호 부여.
         *
         * LATEST:
         * total, total-1, ...
         *
         * OLDEST:
         * 1, 2, ...
         */
        long total =
                page.getTotalElements();

        long offset =
                pageable.getOffset();


        Sort.Order createdAtOrder =
                pageable.getSort()
                        .getOrderFor(
                                "createdAt"
                        );


        if (createdAtOrder != null
                && createdAtOrder.isAscending()) {

            long start =
                    offset + 1;

            AtomicLong counter =
                    new AtomicLong(
                            start
                    );

            page.forEach(
                    notice ->
                            notice.setNo(
                                    counter.getAndIncrement()
                            )
            );

        } else {

            long start =
                    total - offset;

            AtomicLong counter =
                    new AtomicLong(
                            start
                    );

            page.forEach(
                    notice ->
                            notice.setNo(
                                    counter.getAndDecrement()
                            )
            );
        }

        return page;
    }

    @Transactional(readOnly = true)
    public NoticeDetailResponse getNoticeDetail(String noticeId) {

        Notice notice = noticeQueryRepository.findById(noticeId).orElseThrow(
                () -> new CustomException(ErrorCode.NOTICE_NOT_FOUND)
        );

        return NoticeDetailResponse.builder()
                .id(notice.getId())
                .noticeCategoryId(notice.getCategory().getId())
                .title(notice.getTitle())
                .author(notice.getAdmin().getId())
                .content(notice.getContent())
                .createdAt(notice.getCreatedAt())
                .build();
    }

    /*
    * 공지사항 카테고리 조회지만, 공지사항 생성/수정 기능에만 사용될거라 판단하여 NoticeQuery 쪽에서 처리
    * */
    @Transactional(readOnly = true)
    public List<NoticeCategoryResponse> readAllNoticeCategory() {

        return noticeCategoryQueryRepository.findAllBy();
    }

    private String normalizeEventId(
            String eventId
    ) {

        if (eventId == null
                || eventId.isBlank()) {

            return null;
        }


        return eventId;
    }
}
