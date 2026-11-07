package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 신청의 구매 줄. 한 줄은 상품(단품·패키지) 한 개다. 같은 상품을 두 개 사면 줄도 두 개다.
 *
 * 참가 항목은 줄에 묶인다. 단품 줄은 항목 1개, 패키지 줄은 하위 종목마다 1개다.
 * 기념품은 줄마다 고르고, 금액은 그 줄의 가격 계산 결과다. 줄 금액의 합이 생성 때 신청 계약금액이다.
 * 신청 행의 종목·기념품은 첫 줄(대표 상품) 값이므로 상품 판정·가격·정원에는 이 줄을 읽는다.
 *
 * @param <E> 대회 타입
 * @param <R> 신청 타입
 * @param <C> 종목 타입
 */
@Getter
@MappedSuperclass
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class RegistrationItemBase<
        E extends EventBase,
        R extends RegistrationBase<?, ?, ?, ?, ?>,
        C extends EventCategoryBase<?>> {

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

    /** 구매한 상품(SINGLE·PACKAGE). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_category_id", nullable = false)
    protected C eventCategory;

    /** 신청 안 순서. 1부터 센다. */
    @Column(name = "sort_order", nullable = false)
    protected int sortOrder;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    protected BigDecimal amount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "souvenir_json", columnDefinition = "JSON")
    protected List<SouvenirJson> souvenirJson;

    /** 부분 취소 시각. null이면 살아 있는 줄이다. 취소한 줄과 그 참가 항목은 지우지 않고 남긴다. */
    @Column(name = "canceled_at")
    protected LocalDateTime canceledAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    protected LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    protected LocalDateTime updatedAt;

    public boolean isCanceled() {
        return canceledAt != null;
    }
}
