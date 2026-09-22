package kr.co.teambrain.marvelrun.admin.payment.command.application.domain;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import kr.co.teambrain.marvelrun.common.entity.PaymentProcessLogBase;
/** 기존 append-only 로그 테이블의 관리자 매핑이다. */
@Entity
@Getter
@SuperBuilder
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Table(name = "payment_process_log")
public class PaymentProcessLog extends PaymentProcessLogBase { }
