package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.Builder;

/** 대회별 신청 분류 코드와 관리자 공통 선택지의 노출 우선순위·표시 순서를 저장한다. */
@Getter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
public abstract class ReferenceFilterBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, length = 40)
    protected String id;

    // 코드는 대회 범위에서 구분하며 신청·단체에 연결 이력을 남긴다.
    @Column(name = "event_id", nullable = false, length = 40)
    protected String eventId;

    @Column(nullable = false, length = 50)
    protected String code;

    @Column(name = "display_name", nullable = false, length = 100)
    protected String displayName;

    // priority는 노출 상한 검색, sortOrder는 선택지 표시 순서에 사용한다.
    @Builder.Default
    @Column(nullable = false)
    protected int priority = 100;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    protected int sortOrder = 100;
}
