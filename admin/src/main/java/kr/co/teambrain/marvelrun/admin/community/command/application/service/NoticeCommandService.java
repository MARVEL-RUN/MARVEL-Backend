package kr.co.teambrain.marvelrun.admin.community.command.application.service;

import kr.co.teambrain.marvelrun.admin.auth.command.application.domain.Admin;
import kr.co.teambrain.marvelrun.admin.auth.command.repository.AdminCommandRepository;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.community.command.application.dto.NoticeUpdate;
import kr.co.teambrain.marvelrun.admin.event.command.domain.Event;
import kr.co.teambrain.marvelrun.admin.security.util.AdminInfoUtil;
import kr.co.teambrain.marvelrun.admin.auth.command.application.domain.Admin;
import kr.co.teambrain.marvelrun.admin.auth.command.repository.AdminCommandRepository;
import kr.co.teambrain.marvelrun.admin.community.command.application.domain.Notice;
import kr.co.teambrain.marvelrun.admin.community.command.application.domain.NoticeCategory;
import kr.co.teambrain.marvelrun.admin.community.command.application.dto.NoticeCreate;
import kr.co.teambrain.marvelrun.admin.community.command.repository.NoticeCategoryCommandRepository;
import kr.co.teambrain.marvelrun.admin.community.command.repository.NoticeCommandRepository;
import kr.co.teambrain.marvelrun.admin.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.admin.security.util.AdminInfoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class NoticeCommandService {

    private final NoticeCommandRepository noticeCommandRepository;
    private final NoticeCategoryCommandRepository noticeCategoryCommandRepository;
    private final EventCommandRepository eventCommandRepository;
    private final AdminCommandRepository adminCommandRepository;


    @Transactional
    // 공지사항 생성
    public String createNotice(NoticeCreate noticeCreate, String eventId) {

        NoticeCategory category = getNoticeCategory(noticeCreate.getCategoryId());

        Admin admin = getAdmin(AdminInfoUtil.getAdminId());

        Notice newNotice = Notice.builder()
                .category(category)
                .admin(admin)
                .content(noticeCreate.getContent())
                .title(noticeCreate.getTitle())
                .build();

        // 대회 공지사항 등록인 경우 event 추가
        if(eventId != null) {
            Event event = getEvent(eventId);
            newNotice.insertEvent(event);
        }

        noticeCommandRepository.save(newNotice);

        return newNotice.getId();
    }


    @Transactional
    public void updateNotice(NoticeUpdate noticeUpdate, String noticeId) {
        Notice notice = getNotice(noticeId);

        NoticeCategory category = getNoticeCategory(noticeUpdate.getCategoryId());

        notice.updateNotice(noticeUpdate.getTitle(), noticeUpdate.getContent(), category);

        noticeCommandRepository.save(notice);
    }

    @Transactional
    public void deleteNotice(String noticeId) {
        noticeCommandRepository.deleteById(noticeId);
    }


    private NoticeCategory getNoticeCategory(String categoryId) {
        return noticeCategoryCommandRepository.findById(categoryId).orElseThrow(
                () ->new CustomException(ErrorCode.NOTICE_CATEGORY_NOT_FOUND)
        );
    }

    private Event getEvent(String eventId) {
        return eventCommandRepository.findById(eventId).orElseThrow(
                () -> new CustomException(ErrorCode.EVENT_NOT_FOUND)
        );
    }

    private Admin getAdmin(String adminId) {
        return adminCommandRepository.findById(adminId).orElseThrow(
                () -> new CustomException(ErrorCode.ADMIN_NOT_FOUND)
        );
    }

    private Notice getNotice(String noticeId) {
        return noticeCommandRepository.findById(noticeId).orElseThrow(
                () -> new CustomException(ErrorCode.NOTICE_NOT_FOUND)
        );
    }
}
