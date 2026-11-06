package kr.co.teambrain.marvelrun.admin.event.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.common.entity.RegistrationItemBase;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/*
 * 사용자 서버 참조 파일: user/.../event/command/application/domain/RegistrationItem.java
 * 신청의 구매 줄. 줄을 만들고 상품을 바꾸는 것은 사용자 앱의 신청·상향 흐름이다.
 * 관리자 앱은 부분 취소 표시(canceled_at)만 쓴다.
 */
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

    /** 이 줄을 부분 취소한다. 줄과 참가 항목은 지우지 않는다. 이미 취소한 줄은 호출자가 먼저 거른다. */
    public void cancel(LocalDateTime now) {
        if (canceledAt != null || now == null) {
            throw new CustomException(ErrorCode.REGISTRATION_ITEM_ALREADY_CANCELED, " itemId=" + id);
        }
        this.canceledAt = now;
    }
}
