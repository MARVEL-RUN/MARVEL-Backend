package kr.co.teambrain.marvelrun.admin.user.query.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyModels;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OrganizationMemberDto {
    /** 사용자 관점의 기간·외부결제 제한이며 관리자 명령 권한과는 별개다. */
    private RegistrationActionPolicyModels.OrganizationMemberPolicy memberPolicy;

    private Long listNumber;         // 번호
    private String registrationId;
    private String name;             // 성명
    private String birth;            // 생년월일
    private String gender;           // 성별
    private String courseName;       // 코스
    private String souvenirName;     // 기념품
    private String souvenirSize;     // 기념품 사이즈
    private String phoneNumber;      // 연락처
    private String marketingConsent; // 마케팅 동의 (Y/N)
    private String status;           // 신청상태
    private LocalDateTime createdAt; // 신청일시
    private BigDecimal amount;
}