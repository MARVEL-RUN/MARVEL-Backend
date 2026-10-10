package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 신청일 구간별 작업 제한 테이블의 컬럼을 공유한다. 정책 판정 책임은 각 서버에 둔다. */
@Getter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
public abstract class RegistrationActionPolicyBase {

    // 신규 정책은 UUID를 자동 생성한다. 수동 SQL 입력도 UUID를 사용한다.
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    // 실제 FK는 event(id)를 참조하며 삭제·ID 변경을 RESTRICT한다(02 DB 변경 SQL).
    @Column(name = "event_id", nullable = false, length = 40)
    protected String eventId;

    @Column(name = "action_type", nullable = false, length = 16)
    protected String actionType;

    // 모든 정책 시각은 KST이며 대상 신청일 종료는 제외한다.
    @Column(name = "registration_start_at", nullable = false)
    protected LocalDateTime registrationStartAt;

    @Column(name = "registration_end_at", nullable = false)
    protected LocalDateTime registrationEndAt;

    @Column(name = "effective_from", nullable = false)
    protected LocalDateTime effectiveFrom;

    @Column(name = "enabled", nullable = false)
    protected boolean enabled;
}
