package kr.co.teambrain.marvelrun.user.event.command.application.domain;


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

    public void updateEmail(String email) {
        this.email = email;
    }

    public void applyProfileModification(String leaderName, String leaderBirth, String leaderPhNum, String email, String address, String addressDetail) {
        this.leaderName = leaderName;
        this.leaderBirth = leaderBirth;
        this.leaderPhNum = leaderPhNum;
        this.email = email;
        this.address = address;
        this.addressDetail = addressDetail;
    }
}