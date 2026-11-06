package kr.co.teambrain.marvelrun.user.event.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.co.teambrain.marvelrun.common.entity.RegistrationItemBase;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.List;

/** 신청의 구매 줄. 상품 한 개와 그 줄의 기념품·금액을 담는다. */
@Getter
@Entity
@SuperBuilder
@Table(
        name = "registration_item",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_registration_item_order", columnNames = {"registration_id", "sort_order"})
        },
        indexes = {
                @Index(name = "idx_registration_item_event_category", columnList = "event_id,event_category_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RegistrationItem extends RegistrationItemBase<Event, Registration, EventCategory> {

    /** 검증과 가격 계산을 마친 줄을 신청에 묶어 만든다. sortOrder는 1부터 센다. */
    public static RegistrationItem create(
            Registration registration,
            int sortOrder,
            EventCategory product,
            List<SouvenirJson> souvenirs,
            BigDecimal amount
    ) {
        return RegistrationItem.builder()
                .event(registration.getEvent())
                .registration(registration)
                .eventCategory(product)
                .sortOrder(sortOrder)
                .souvenirJson(List.copyOf(souvenirs))
                .amount(amount)
                .build();
    }

    /** 단품 → 패키지 상향 승인 때 줄의 상품과 금액을 패키지로 바꾼다. 기념품은 그대로 둔다. */
    public void applyPackageUpgrade(EventCategory packageCategory, BigDecimal packageAmount) {
        this.eventCategory = packageCategory;
        this.amount = packageAmount;
    }
}
