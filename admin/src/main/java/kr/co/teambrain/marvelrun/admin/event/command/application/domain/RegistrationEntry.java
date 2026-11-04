package kr.co.teambrain.marvelrun.admin.event.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.co.teambrain.marvelrun.common.entity.RegistrationEntryBase;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/*
 * 사용자 서버 참조 파일: user/.../event/command/application/domain/RegistrationEntry.java
 * 신청의 종목별 참가 항목. 관리자 배번 발급·명단에서 쓴다.
 */
@Getter
@Entity
@SuperBuilder
@Table(
        name = "registration_entry",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_registration_entry_category", columnNames = {"registration_id", "event_category_id"}),
                @UniqueConstraint(name = "uk_registration_entry_bib", columnNames = {"event_id", "bib_number"})
        },
        indexes = {
                @Index(name = "idx_registration_entry_event_category", columnList = "event_id,event_category_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RegistrationEntry extends RegistrationEntryBase<Event, Registration, EventCategory> {
}
