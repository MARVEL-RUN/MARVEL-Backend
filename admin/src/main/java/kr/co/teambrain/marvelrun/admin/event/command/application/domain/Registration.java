package kr.co.teambrain.marvelrun.admin.event.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.admin.user.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.admin.user.command.application.domain.User;
import kr.co.teambrain.marvelrun.common.entity.RegistrationBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(name = "registration")
@NoArgsConstructor(access = PROTECTED)
public class Registration extends RegistrationBase<User, Event, EventCategory, Organization, Souvenir> {

    public void resetPasswordByAdmin(String newPassword) {
        this.password = newPassword;
    }
    /**
     * 관리자에 의한 참가자 기본 정보 강제 수정 및 메모 이력 기록
     */
    public void modifyBasicInfoByAdmin(
            String name, String phNum, String email, String birth, GenderClass gender,
            String address, String addressDetail,
            String guardianName, String guardianPhNum, String guardianRelationship,
            LocalDateTime now
    ) {
        this.name = name;
        this.phNum = phNum;
        this.email = email;
        this.birth = birth;
        this.gender = gender;
        this.address = address;
        this.addressDetail = addressDetail;
        this.guardianName = guardianName;
        this.guardianPhNum = guardianPhNum;
        this.guardianRelationship = guardianRelationship;

        String timestamp = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String logMessage = String.format("[%s] 관리자에 의한 참가자 기본 정보(개인정보/주소 등) 강제 수정", timestamp);

        if (this.detailMemo == null || this.detailMemo.isBlank()) {
            this.detailMemo = logMessage;
        } else {
            this.detailMemo = this.detailMemo + "\n" + logMessage;
        }
    }

    public void expireByAdmin(LocalDateTime now) {
        this.softDeleted = true;
        this.status = RegistrationStatus.EXPIRED; // 요청하신 EXPIRED 상태 전이

        String timestamp = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String logMessage = String.format("[%s] 관리자에 의한 결제 대기 신청건 삭제 (EXPIRED)", timestamp);
        if (this.detailMemo == null || this.detailMemo.isBlank()) {
            this.detailMemo = logMessage;
        } else {
            this.detailMemo = this.detailMemo + "\n" + logMessage;
        }
    }
}
