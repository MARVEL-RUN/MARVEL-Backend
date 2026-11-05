package kr.co.teambrain.marvelrun.admin.event.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.co.teambrain.marvelrun.common.entity.RegistrationEntryBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/*
 * 사용자 서버 참조 파일: user/.../event/command/application/domain/RegistrationEntry.java
 * 구매 줄의 종목별 참가 항목. 관리자 배번 발급·명단에서 쓴다. 신청자 정보가 바뀌어도 항목은 따로 둔다.
 * 배번은 JDBC로 발급하며, 배번 컬럼은 RegistrationEntryBase에서 UPDATE 대상이 아니다.
 */
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

    /** 관리자가 상품 받는 사람 정보를 고친다. 배번은 그대로 둔다. */
    public void modifyRecipientByAdmin(String name, String phNum, String birth, GenderClass gender) {
        this.name = name;
        this.phNum = phNum;
        this.birth = birth;
        this.gender = gender;
    }
}
