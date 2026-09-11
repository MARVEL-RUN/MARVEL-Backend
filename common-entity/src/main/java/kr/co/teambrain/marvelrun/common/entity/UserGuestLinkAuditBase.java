package kr.co.teambrain.marvelrun.common.entity;


import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

@Getter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
public class UserGuestLinkAuditBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id") // PK
    private Long id;

    @Column(name = "user_id", nullable = false, length = 40)
    private String userId;

    /** 자동연동 정책이라면 항상 AUTO_LINK */
    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 16)
    private Decision decision;

    @Column(name = "irreversible_confirmed", nullable = false)
    private boolean irreversibleConfirmed;

    /** uniqueInfo로 조회된 전체 후보 수(= 연동 범위 규모) */
    @Column(name = "candidate_count", nullable = false)
    private int candidateCount;

    /** 이번 커밋에서 실제로 user_id가 NULL -> user로 세팅된 건수 */
    @Column(name = "newly_linked_count", nullable = false)
    private int newlyLinkedCount;

    /** 후보 id 리스트 정렬 후 sha256(digest) */
    @Column(name = "digest_sha256", length = 64)
    private String digestSha256;

    // (선택) Instant를 DATETIME(6)로 고정하고 싶으면 columnDefinition 추가
    @Column(name = "confirmed_at", nullable = false /*, columnDefinition = "DATETIME(6)" */)
    private Instant confirmedAt;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "user_agent", length = 256)
    private String userAgent;

    @Column(name = "ui_copy_version", length = 64)
    private String uiCopyVersion;

    public enum Decision {
        LINK_ALL, LINK_NONE, AUTO_LINK
    }
}