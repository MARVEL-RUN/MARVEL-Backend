package kr.co.teambrain.marvelrun.user.event.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.EventBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

/**
 * 대회 정보와 신청 가능 상태를 관리한다.
 *
 * 실제 정원 수량은 Capacity가 관리하며,
 * 전체 정원 도달 여부를 확인한 서비스가 마감을 요청한다.
 */
@Getter
@Entity
@Table(name = "event")
@NoArgsConstructor(access = PROTECTED)
public class Event extends EventBase {

    /**
     * 전체 정원 도달에 따라 신규 신청을 마감한다.
     *
     * OPEN 상태만 CLOSED로 변경한다.
     * 다른 운영 상태를 덮어쓰거나 자동으로 접수를 재개하지 않는다.
     *
     * 호출 서비스에서 대회 잠금과 전체 정원 도달 여부를 확인해야 한다.
     */
    public void closeRegistrationForCapacity() {
        if (eventStatus == EventStatus.OPEN) {
            eventStatus = EventStatus.CLOSED;
        }
    }
}