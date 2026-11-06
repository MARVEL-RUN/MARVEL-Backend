package kr.co.teambrain.marvelrun.admin.community.query.service;

import kr.co.teambrain.marvelrun.admin.auth.command.application.domain.Admin;
import kr.co.teambrain.marvelrun.admin.community.command.application.domain.Notice;
import kr.co.teambrain.marvelrun.admin.community.command.application.domain.NoticeCategory;
import kr.co.teambrain.marvelrun.admin.community.query.dto.response.NoticeDetailResponse;
import kr.co.teambrain.marvelrun.admin.community.query.repository.NoticeCategoryQueryRepository;
import kr.co.teambrain.marvelrun.admin.community.query.repository.NoticeQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 관리자 공지 상세 응답의 eventId. 대회 공지는 그 대회 id, 대회가 없는 공지는 null이다. */
@ExtendWith(MockitoExtension.class)
class NoticeQueryServiceTest {

    @Mock private NoticeQueryRepository noticeQueryRepository;
    @Mock private NoticeCategoryQueryRepository noticeCategoryQueryRepository;
    @InjectMocks private NoticeQueryService noticeQueryService;

    @Test
    void detailCarriesEventIdOfEventNotice() {
        Event event = mock(Event.class);
        when(event.getId()).thenReturn("test-marvelrun");
        Notice notice = notice(event);
        when(noticeQueryRepository.findById("n1")).thenReturn(Optional.of(notice));

        NoticeDetailResponse response = noticeQueryService.getNoticeDetail("n1");

        assertThat(response.getEventId()).isEqualTo("test-marvelrun");
    }

    @Test
    void detailEventIdIsNullWithoutEvent() {
        Notice notice = notice(null);
        when(noticeQueryRepository.findById("n1")).thenReturn(Optional.of(notice));

        NoticeDetailResponse response = noticeQueryService.getNoticeDetail("n1");

        assertThat(response.getEventId()).isNull();
    }

    private Notice notice(Event event) {
        NoticeCategory category = mock(NoticeCategory.class);
        Admin admin = mock(Admin.class);
        Notice notice = mock(Notice.class);
        when(notice.getCategory()).thenReturn(category);
        when(notice.getAdmin()).thenReturn(admin);
        when(notice.getEvent()).thenReturn(event);
        return notice;
    }
}
