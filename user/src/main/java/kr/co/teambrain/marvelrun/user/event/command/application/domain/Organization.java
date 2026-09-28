package kr.co.teambrain.marvelrun.user.event.command.application.domain;


import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.OrganizationBase;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import static lombok.AccessLevel.PROTECTED;

/** 단체 계정과 대표 정보를 관리하며 본인확인 후 전달받은 비밀번호 해시를 저장한다. */
@Getter
@Entity
@SuperBuilder
@Table(name = "organization")
@NoArgsConstructor(access = PROTECTED)
public class Organization
        extends OrganizationBase<Event> {

    /** 단체 계정의 비밀번호만 변경하며 구성원 Registration에는 전파하지 않는다. */
    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    
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
