package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import static lombok.AccessLevel.PROTECTED;

@Getter
@MappedSuperclass
@SuperBuilder
@NoArgsConstructor(access = PROTECTED)
public abstract class QuestionAttachmentBase<
        Q extends QuestionBase<?, ?>,
        AT extends AttachmentBase
        > {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
            name = "id",
            nullable = false,
            length = 40
    )
    protected String id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "question_id",
            nullable = false
    )
    protected Q question;

    @OneToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "attachment_id",
            nullable = false,
            unique = true
    )
    protected AT attachment;

    @Column(
            name = "display_order",
            nullable = false
    )
    protected Integer displayOrder;

    protected void updateDisplayOrder(
            int displayOrder
    ) {
        this.displayOrder = displayOrder;
    }
}