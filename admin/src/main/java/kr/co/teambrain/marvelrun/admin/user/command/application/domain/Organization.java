package kr.co.teambrain.marvelrun.admin.user.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.common.entity.OrganizationBase;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

/** 관리자 변경을 반영하며 비밀번호는 서비스에서 전달한 해시로 저장한다. */
@Getter
@Entity
@Table(name = "organization")
@NoArgsConstructor(access = PROTECTED)
public class Organization extends OrganizationBase<Event> {

    /** 서비스에서 검증하고 해시로 변환한 비밀번호를 저장한다. */
    public void resetPasswordByAdmin(String encodedPassword) {
        this.password = encodedPassword;
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
