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
public abstract class AttachmentBase<Q extends QuestionBase, A extends AnswerBase> {
    @Id
    @Column(name = "url", nullable = false)
    protected String url;

    @Column(name = "origin_name", nullable = false)
    protected String originName;

    @Column(name = "origin_mb", nullable = false)
    protected Integer originMb;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id")
    protected Q question;

//    @ManyToOne(fetch = FetchType.LAZY)
//    @JoinColumn(name = "notice_id")
//    protected N notice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "answer_id")
    protected A answer;

}
