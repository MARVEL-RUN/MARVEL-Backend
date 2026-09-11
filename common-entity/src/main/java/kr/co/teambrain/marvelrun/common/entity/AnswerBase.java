package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDateTime;

import static lombok.AccessLevel.PROTECTED;


@Getter
@Setter
@MappedSuperclass
@NoArgsConstructor(access = PROTECTED)
@SuperBuilder(toBuilder = true)
public abstract class AnswerBase<A extends AdminBase, Q extends QuestionBase> {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @Column(name = "title", nullable = false, length = 40)
    protected String title;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "admin_id",
            nullable = false
    )
    protected A admin;

    @OneToOne( // 질문에 답변 달리면 그걸로 끝. 일대일 관계
            fetch = FetchType.EAGER,
            optional = false
    )
    @JoinColumn(
            name = "question_id",
            nullable = false
    )
    protected Q question;

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

}