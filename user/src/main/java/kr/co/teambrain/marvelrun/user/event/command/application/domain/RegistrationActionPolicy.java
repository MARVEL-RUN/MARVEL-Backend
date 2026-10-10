package kr.co.teambrain.marvelrun.user.event.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.RegistrationActionPolicyBase;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import static lombok.AccessLevel.PROTECTED;

/** 수동 관리되는 신청일 구간별 작업 제한 정책을 JPA로 조회한다. */
@Getter
@Entity
@SuperBuilder
@NoArgsConstructor(access = PROTECTED)
@Table(name = "registration_action_policy", indexes = {
        @Index(name = "idx_registration_action_policy_lookup", columnList = "event_id,enabled,action_type")
})
public class RegistrationActionPolicy extends RegistrationActionPolicyBase {
}
