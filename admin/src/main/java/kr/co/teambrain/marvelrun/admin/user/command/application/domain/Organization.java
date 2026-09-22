package kr.co.teambrain.marvelrun.admin.user.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.common.entity.OrganizationBase;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(name = "organization")
@NoArgsConstructor(access = PROTECTED)
public class Organization extends OrganizationBase<Event> {

    public void resetPasswordByAdmin(String newPassword) {
        this.password = newPassword;
    }

    public void resetLoginIdByAdmin(String newLoginId) {
        this.loginId = newLoginId;
    }

    public void modifyInfoByAdmin(
            String groupName, String leaderName, String leaderBirth, String leaderPhNum,
            String email, String address, String addressDetail, boolean guardianConsent
    ) {
        this.groupName = groupName;
        this.leaderName = leaderName;
        this.leaderBirth = leaderBirth;
        this.leaderPhNum = leaderPhNum;
        this.email = email;
        this.address = address;
        this.addressDetail = addressDetail;
        this.guardianConsent = guardianConsent;
    }
}
