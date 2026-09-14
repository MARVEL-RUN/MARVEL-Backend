package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.phone_auth_policy.PhoneAuthBulkTargetScope;
import kr.co.teambrain.marvelrun.common.inheritance_enum.phone_auth_policy.PhoneAuthPolicyAuditActionType;

import java.time.LocalDateTime;

@Getter
@MappedSuperclass
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PhoneAuthPolicyAuditBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 36)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 50)
    private PhoneAuthPolicyAuditActionType actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", length = 50)
    private PhoneAuthBulkTargetScope scope;

    @Column(name = "admin_id", length = 36)
    private String adminId;

    @Column(name = "reason", length = 500)
    private String reason;
    
    /** id 목록 List를 직렬화하여 String으로 저장 */
    @Lob
    @Column(name = "target_event_ids_json", columnDefinition = "json")
    private String targetEventIdsJson;

    @Column(name = "updated_count")
    private Integer updatedCount;
    
    
    /** EventPhoneAuthBulkUpdateAuditDetail을 직렬화하여 String으로 저장 */
    @Column(name = "detail_json", columnDefinition = "json")
    private String detailJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected PhoneAuthPolicyAuditBase(
            String id,
            PhoneAuthPolicyAuditActionType actionType,
            PhoneAuthBulkTargetScope scope,
            String adminId,
            String reason,
            String targetEventIdsJson,
            Integer updatedCount,
            String detailJson,
            LocalDateTime createdAt
    ) {
        this.id = id;
        this.actionType = actionType;
        this.scope = scope;
        this.adminId = adminId;
        this.reason = reason;
        this.targetEventIdsJson = targetEventIdsJson;
        this.updatedCount = updatedCount;
        this.detailJson = detailJson;
        this.createdAt = createdAt;
    }
}