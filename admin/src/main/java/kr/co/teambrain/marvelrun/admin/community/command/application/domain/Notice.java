package kr.co.teambrain.marvelrun.admin.community.command.application.domain;


import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.admin.auth.command.application.domain.Admin;
import kr.co.teambrain.marvelrun.admin.event.command.domain.Event;
import kr.co.teambrain.marvelrun.common.entity.NoticeBase;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "notice")
@AllArgsConstructor
public class Notice extends NoticeBase<NoticeCategory, Admin, Event> {

    public void updateViewCount() {
        this.viewCount += 1L;
    }

    @Builder
    public Notice(NoticeCategory category, Admin admin, Event event, String content, String title) {
        this.category = category;
        this.admin = admin;
        this.event = event;
        this.content = content;
        this.title = title;
    }

    public void updateNotice(String title, String content, NoticeCategory category) {
        this.title = title;
        this.content = content;
        this.category = category;
    }

    public void insertEvent(Event event) {
        this.event = event;
    }

}
