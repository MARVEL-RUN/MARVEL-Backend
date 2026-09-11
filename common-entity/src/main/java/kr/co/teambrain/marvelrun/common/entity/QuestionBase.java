package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDateTime;

import static lombok.AccessLevel.PROTECTED;

@Getter
@MappedSuperclass
@NoArgsConstructor(access = PROTECTED)
@SuperBuilder
public abstract class QuestionBase<U extends UserBase, E extends EventBase> {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @ManyToOne(
            fetch = FetchType.LAZY
    )
    @JoinColumn(
            name = "user_id"
    )
    protected U user;

    @ManyToOne(
            fetch = FetchType.LAZY
    )
    @JoinColumn(
            name = "event_id"
    )
    protected E event;

    @Column(
            name = "title",
            nullable = false,
            length = 50
    )
    protected String title;

    /* user가 없는 경우 사용(비회원 전용, V0 전용)*/
    @Column(
            name = "author_name",
            nullable = false,
            length = 20
    )
    protected String authorName;

    @Lob
    @Column(
            name = "content",
            nullable = false
    )
    protected String content;

    @Column(
            name = "created_at",
            nullable = false
    )
    @CreationTimestamp
    protected LocalDateTime createdAt;

    @Column(
            name = "password",
            nullable = true,
            length = 65
    )
    protected String password;

    @Column(
            name = "is_secret",
            nullable = false
    )
    protected Boolean isSecret = true; // 비밀글 여부

    @Column(
            name = "is_answered",
            nullable = false
    )
    protected Boolean isAnswered = false; // 답변 완료 여부
}
