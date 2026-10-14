package kr.co.teambrain.marvelrun.admin.event.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.co.teambrain.marvelrun.common.entity.EventCategoryStartGroupBase;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import static lombok.AccessLevel.PROTECTED;

/** 종목 ID만 참조하는 출발 그룹 엔티티로 신규 연관관계를 만들지 않는다. */
@Entity
@Table(name = "event_category_start_group", uniqueConstraints = {
        @UniqueConstraint(name = "uk_start_group_category_code", columnNames = {"event_category_id", "code"}),
        @UniqueConstraint(name = "uk_start_group_category_order", columnNames = {"event_category_id", "sort_order"})
})
@SuperBuilder
@NoArgsConstructor(access = PROTECTED)
public class EventCategoryStartGroup extends EventCategoryStartGroupBase {
}
