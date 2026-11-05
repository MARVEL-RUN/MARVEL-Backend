package kr.co.teambrain.marvelrun.user.event.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.co.teambrain.marvelrun.common.entity.RegistrationEntryBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationEntryDraft;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** 구매 줄의 종목별 참가 항목. 받는 사람과 배번을 담는다. 배번은 관리자만 발급하므로 사용자 앱은 쓰지 않는다. */
@Getter
@Entity
@SuperBuilder
@Table(
        name = "registration_entry",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_registration_entry_item_category", columnNames = {"registration_item_id", "event_category_id"}),
                @UniqueConstraint(name = "uk_registration_entry_bib", columnNames = {"event_id", "bib_number"})
        },
        indexes = {
                @Index(name = "idx_registration_entry_event_category", columnList = "event_id,event_category_id"),
                @Index(name = "idx_registration_entry_registration", columnList = "registration_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RegistrationEntry extends RegistrationEntryBase<Event, Registration, EventCategory, RegistrationItem> {

    /** 검증을 마친 항목 값으로 구매 줄에 묶인 참가 항목을 만든다. 배번은 발급 때 채운다. */
    public static RegistrationEntry create(RegistrationItem item, RegistrationEntryDraft draft) {
        return RegistrationEntry.builder()
                .event(item.getEvent())
                .registration(item.getRegistration())
                .registrationItem(item)
                .eventCategory(draft.eventCategory())
                .name(draft.name())
                .phNum(draft.phNum())
                .birth(draft.birth())
                .gender(draft.gender())
                .build();
    }

    /** 받는 사람 값을 바꾼다. 배번은 그대로 둔다. */
    public void updateRecipient(String name, String phNum, String birth, GenderClass gender) {
        this.name = name;
        this.phNum = phNum;
        this.birth = birth;
        this.gender = gender;
    }
}
