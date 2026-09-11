package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import kr.co.teambrain.marvelrun.common.inheritance_enum.phone_auth_policy.PhoneAuthGlobalPolicy;

import java.time.LocalDateTime;

/** 서비스 전역적인 정책 설정 */
@Getter
@MappedSuperclass
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class PhoneAuthGlobalPolicySettingBase {

    /** id를 대체하는, 고정 세팅 row 명칭을 저장해두기 위한 String(추후 Set 등으로 확장될 수 있음)*/
    public static final String SETTING_KEY =
            "PHONE_AUTH_GLOBAL_POLICY";

    @Id
    @Column(name = "setting_key", nullable = false, length = 50)
    private String settingKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy", nullable = false, length = 50)
    private PhoneAuthGlobalPolicy policy;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected PhoneAuthGlobalPolicySettingBase(
            String settingKey,
            PhoneAuthGlobalPolicy policy,
            String updatedBy,
            LocalDateTime updatedAt
    ) {
        this.settingKey = settingKey;
        this.policy = policy;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    protected void changePolicy(
            PhoneAuthGlobalPolicy policy,
            String updatedBy,
            LocalDateTime updatedAt
    ) {
        this.policy = policy;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }
}