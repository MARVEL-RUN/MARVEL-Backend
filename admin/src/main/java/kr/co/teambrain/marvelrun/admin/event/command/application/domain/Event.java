package kr.co.teambrain.marvelrun.admin.event.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.EventBase;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

/*
 * 사용자 서버 참조 시각: 2026-10-02 17:18:56 KST
 * 참조 파일: user/src/main/java/kr/co/teambrain/marvelrun/user/event/command/application/domain/Event.java
 * 전체 정원 도달 시 OPEN만 CLOSED로 변경하는 규칙을 유지한다.
 */
@Getter
@Entity
@Table(name = "event")
@NoArgsConstructor(access = PROTECTED)
public class Event extends EventBase {
    /** 확보가 전체 정원에 도달하면 신규 신청을 마감한다. */
    public void closeRegistrationForCapacity() {
        if (eventStatus == kr.co.teambrain.marvelrun.common.inheritance_enum.EventStatus.OPEN) {
            eventStatus = kr.co.teambrain.marvelrun.common.inheritance_enum.EventStatus.CLOSED;
        }
    }
}
