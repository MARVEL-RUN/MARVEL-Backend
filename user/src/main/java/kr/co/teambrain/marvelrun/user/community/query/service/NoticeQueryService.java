package kr.co.teambrain.marvelrun.user.community.query.service;


import kr.co.teambrain.marvelrun.user.common.dto.PasswordInputRequest;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.community.command.application.domain.Notice;
import kr.co.teambrain.marvelrun.user.community.query.domain.NoticeSearchTarget;
import kr.co.teambrain.marvelrun.user.community.query.domain.QuestionSearchTarget;
import kr.co.teambrain.marvelrun.user.community.query.dto.NoticeListResponse;
import kr.co.teambrain.marvelrun.user.community.query.dto.PinnedNoticeResponse;
import kr.co.teambrain.marvelrun.user.community.query.dto.response.NoticeDetailResponse;
import kr.co.teambrain.marvelrun.user.community.query.dto.response.NoticeHeaderPageWrapperResponse;
import kr.co.teambrain.marvelrun.user.community.query.repository.NoticeQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
public class NoticeQueryService {
    private final NoticeQueryRepository noticeQueryRepository;

    @Transactional(readOnly = true)
    public NoticeHeaderPageWrapperResponse getNoticePage(
            String eventId,
            NoticeSearchTarget target,
            String keyword,
            int limit,
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
        // 넘버링에 사용할 변수
        long total = page.getTotalElements();
        long start = total - pageable.getOffset();

        AtomicLong counter = new AtomicLong(start);
        page.forEach(p -> p.setNo(counter.getAndDecrement())); // Decrement로 감소시키기(최신순이기 때문)

        return new NoticeHeaderPageWrapperResponse(
                this.readPinnedNoticeList(eventId, Limit.of(limit)),
                page
        );
    }

    @Transactional(readOnly = true)
    public NoticeDetailResponse getNoticeDetail(String noticeId) {

        Notice notice = noticeQueryRepository.findById(noticeId).orElseThrow(
                () -> new CustomException(ErrorCode.NOTICE_NOT_FOUND)
        );

        // 임시 설정. 추후 상세히 로직 나누어 구현
        notice.updateViewCount();
        noticeQueryRepository.save(notice);

        return NoticeDetailResponse.builder()
                .id(notice.getId())
                .viewCount(notice.getViewCount())
                .noticeCategoryId(notice.getCategory().getId())
                .title(notice.getTitle())
                .author(notice.getAdmin().getName())
                .content(notice.getContent())
                .createdAt(notice.getCreatedAt())
                .build();
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



    private List<PinnedNoticeResponse> readPinnedNoticeList(String eventId, Limit limit) {

        List<PinnedNoticeResponse> pinnedNoticeHeaderList;

        if(eventId == null) {
            pinnedNoticeHeaderList = noticeQueryRepository.findPinnedNotices(null, limit);
        } else {
            pinnedNoticeHeaderList = noticeQueryRepository.findPinnedNotices(eventId, limit);
        }


        return pinnedNoticeHeaderList;
    }


}
