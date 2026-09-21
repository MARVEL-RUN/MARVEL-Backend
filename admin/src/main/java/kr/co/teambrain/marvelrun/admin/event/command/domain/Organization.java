package kr.co.teambrain.marvelrun.admin.event.command.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.OrganizationBase;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@SuperBuilder
@Table(name = "organization")
@NoArgsConstructor(access = PROTECTED)
public class Organization
        extends OrganizationBase<Event> {

    public void guardianConsentChecked() {
        this.guardianConsent = true; //해당 값은 ture -> false로 바뀔 수 없음
    }

}