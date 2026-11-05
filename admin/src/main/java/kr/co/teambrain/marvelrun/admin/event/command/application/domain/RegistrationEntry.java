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
import org.hibernate.annotations.DynamicUpdate;

/*
 * 사용자 서버 참조 파일: user/.../event/command/application/domain/RegistrationEntry.java
 * 신청의 종목별 참가 항목. 관리자 배번 발급·명단에서 쓴다.
 * 배번은 JDBC로 발급하므로, 바뀐 컬럼만 갱신해 오래된 NULL 배번을 되쓰지 않게 한다.
 */
@Getter
@Entity
@DynamicUpdate
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

    /** 단품 신청의 참가 항목을 신청자 값으로 만든다. 배번은 발급 때 채운다. */
    public static RegistrationEntry forApplicant(Registration registration) {
        return RegistrationEntry.builder()
                .event(registration.getEvent())
                .registration(registration)
                .eventCategory(registration.getEventCategory())
                .name(registration.getName())
                .phNum(registration.getPhNum())
                .birth(registration.getBirth())
                .gender(registration.getGender())
                .build();
    }

    /** 받는 사람 값을 신청자 값으로 맞춘다. 배번은 그대로 둔다. */
    public void followApplicant(Registration registration) {
        this.name = registration.getName();
        this.phNum = registration.getPhNum();
        this.birth = registration.getBirth();
        this.gender = registration.getGender();
    }

    /** 관리자가 패키지 받는 사람 정보를 고친다. 배번은 그대로 둔다. */
    public void modifyRecipientByAdmin(String name, String phNum, String birth, GenderClass gender) {
        this.name = name;
        this.phNum = phNum;
        this.birth = birth;
        this.gender = gender;
    }
}
