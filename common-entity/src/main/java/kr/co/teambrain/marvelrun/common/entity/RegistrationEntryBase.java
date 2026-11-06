package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 구매 줄 하나에 딸린 종목별 참가 항목.
 *
 * 단품 줄은 그 종목으로 1개, 패키지 줄은 하위 종목마다 1개씩 둔다. 항목마다 받는 사람을 따로 입력받는다.
 * 신청자 정보가 바뀌어도 항목은 바뀌지 않는다.
 * 배번·기록증 이름은 받는 사람 이름이다. 배송지는 신청 행의 주소 하나를 쓴다.
 * 신청이 삭제되면 항목도 함께 무효이므로 따로 삭제 표시를 두지 않는다.
 * 배번은 관리자 발급(JDBC)만 쓴다. 엔티티 수정이 먼저 읽어 둔 빈 배번으로 되쓰지 않도록 두 배번 컬럼은 UPDATE에서 뺀다.
 *
 * @param <E> 대회 타입
 * @param <R> 신청 타입
 * @param <C> 종목 타입
 * @param <I> 구매 줄 타입
 */
@Getter
@MappedSuperclass
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class RegistrationEntryBase<
        E extends EventBase,
        R extends RegistrationBase<?, ?, ?, ?, ?>,
        C extends EventCategoryBase<?>,
        I extends RegistrationItemBase<?, ?, ?>> {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    protected E event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "registration_id", nullable = false)
    protected R registration;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "registration_item_id", nullable = false)
    protected I registrationItem;

    /** 이 항목의 종목. 단품 줄이면 그 줄의 상품, 패키지 줄이면 하위 종목이다. 배번 대역의 기준이다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_category_id", nullable = false)
    protected C eventCategory;

    @Column(name = "name", nullable = false, length = 50)
    protected String name;

    @Column(name = "ph_num", nullable = false, length = 14)
    protected String phNum;

    @Column(name = "birth", nullable = false, length = 10)
    protected String birth;

    @Column(name = "gender", nullable = false, length = 2)
    @Enumerated(EnumType.STRING)
    protected GenderClass gender;

    @Column(name = "bib_number", length = 20, updatable = false)
    protected String bibNumber;

    @Column(name = "bib_issued_at", updatable = false)
    protected LocalDateTime bibIssuedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    protected LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    protected LocalDateTime updatedAt;
}
