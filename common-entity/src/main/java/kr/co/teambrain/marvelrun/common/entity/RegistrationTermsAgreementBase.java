package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Getter
@SuperBuilder
@MappedSuperclass
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class RegistrationTermsAgreementBase<R extends RegistrationBase<?, ?, ?, ?, ?>, T extends EventTermsBase<?>> {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "registration_id", nullable = false)
    protected R registration;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_terms_id", nullable = false)
    protected T eventTerms;

    @Column(name = "is_agreed", nullable = false)
    protected Boolean isAgreed; // 개별 약관 동의 여부

    @Column(name = "agreed_at")
    @CreationTimestamp
    protected LocalDateTime agreedAt; // 동의/거부 일시
}