package kr.co.teambrain.marvelrun.admin.event.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.admin.user.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.admin.user.command.application.domain.User;
import kr.co.teambrain.marvelrun.common.entity.RegistrationBase;
import lombok.Getter;
import lombok.experimental.SuperBuilder;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

@Getter
@SuperBuilder
@Entity
@Table(name = "registration")
@NoArgsConstructor(access = PROTECTED)
public class Registration extends RegistrationBase<User, Event, EventCategory, Organization, Souvenir> {

    public void resetPasswordByAdmin(String newPassword) {
        this.password = newPassword;
    }
    /**
     * 자원 반환을 마친 신청의 참가 의무를 없애고 실제 환불 완료까지 순납부액을 보존한다.
     * 호출자는 같은 트랜잭션에서 인증·충돌 검증·예약 반환을 먼저 완료해야 한다.
     * 개인과 단체의 소속 및 과거 결제 원장은 변경하지 않는다.
     */
    public void cancelParticipation() {
        if (softDeleted) {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET);
        }
        if (paidAmount == null || paidAmount.signum() < 0) {
            throw new CustomException(ErrorCode.REGISTRATION_FINANCIAL_STATE_INVALID);
        }
        this.softDeleted = true;
        this.contractAmount = BigDecimal.ZERO;
        this.status = paidAmount.signum() > 0
                ? RegistrationStatus.CANCELLATION_PENDING : RegistrationStatus.CANCELED;
    }

    /**
     * 신청 수정 후 계약금액과 실제 순결제금액으로 금융 상태를 결정한다.
     *
     * 최초 미결제 여부는 paidAmount == 0만으로 판단하지 않는다.
     * HELD 예약은 최초 결제 대기, CONSUMED 예약은 기존 확정 참가로 구분한다.
     *
     * 실제 순결제금액과 과거 Payment·Allocation은 변경하지 않는다.
     *
     * @return 새 계약금액 - 실제 순결제금액
     */
    public BigDecimal reconcileModificationFinancialState(
            ReservationStatus reservationStatus
    ) {
        if (contractAmount == null
                || contractAmount.signum() < 0
                || paidAmount == null
                || paidAmount.signum() < 0) {
            throw new CustomException(
                    ErrorCode.REGISTRATION_FINANCIAL_STATE_INVALID,
                    " 계약금액 또는 순결제금액이 올바르지 않습니다."
            );
        }

        BigDecimal balance = contractAmount.subtract(paidAmount);

        if (softDeleted) {
            if (reservationStatus != ReservationStatus.RELEASED
                    || contractAmount.signum() != 0) {
                throw new CustomException(
                        ErrorCode.REGISTRATION_FINANCIAL_STATE_INVALID,
                        " 제거된 신청의 계약금액 또는 예약 상태가 올바르지 않습니다."
                );
            }

            this.status = paidAmount.signum() > 0
                    ? RegistrationStatus.CANCELLATION_PENDING
                    : RegistrationStatus.CANCELED;

            return balance;
        }

        if (reservationStatus != ReservationStatus.HELD
                && reservationStatus != ReservationStatus.CONSUMED) {
            throw new CustomException(
                    ErrorCode.RESERVATION_STATE_CONFLICT,
                    " 수정 후 금융 상태를 결정할 수 없는 예약 상태입니다."
            );
        }

        /*
         * 정상적인 최초 미결제 HELD 예약에는 순결제금액이 없어야 한다.
         * 이런 불일치를 추가 결제나 환불로 임의 보정하지 않는다.
         */
        if (reservationStatus == ReservationStatus.HELD
                && paidAmount.signum() != 0) {
            throw new CustomException(
                    ErrorCode.REGISTRATION_FINANCIAL_STATE_INVALID,
                    " 최초 홀딩 예약에 순결제금액이 존재합니다."
            );
        }

        if (balance.signum() > 0) {
            this.status = reservationStatus == ReservationStatus.HELD
                    ? RegistrationStatus.PAYMENT_PENDING
                    : RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED;
        } else if (balance.signum() < 0) {
            this.status = RegistrationStatus.PARTIAL_REFUND_REQUIRED;
        } else {
            this.status = RegistrationStatus.CONFIRMED;
        }

        return balance;
    }

    /** 정책 검증과 자원 이동을 마친 관리자 후보만 반영한다. 개인정보·실제 납부액은 보존한다. */
    public void applyAdminRefundCandidate(EventCategory category, List<SouvenirJson> souvenirs, BigDecimal amount) {
        if (softDeleted || category == null || souvenirs == null || souvenirs.stream().anyMatch(Objects::isNull)
                || amount == null || amount.signum() < 0 || contractAmount == null
                || amount.compareTo(contractAmount) >= 0) {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT);
        }
        this.eventCategory = category;
        this.souvenirJson = List.copyOf(souvenirs);
        this.contractAmount = amount;
    }


    /** 검증된 종목·기념품·생년월일 후보를 계약금액과 같은 트랜잭션에 반영한다. */
    public void applyAdminRefundCandidate(EventCategory category, List<SouvenirJson> souvenirs,
                                          String birth, BigDecimal amount) {
        if (birth == null || birth.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT);
        }
        applyAdminRefundCandidate(category, souvenirs, amount);
        this.birth = birth;
    }

    /**
     * 실제 완료된 환불 금액을 신청의 순납부액에서 차감한다.
     *
     * 호출 서비스는 취소 상태와 원결제 귀속을 검증하고,
     * 같은 완료 결과가 중복 반영되지 않도록 보호해야 한다.
     *
     * 이 메서드는 계약금액·삭제 여부·신청 상태·예약을 변경하지 않는다.
     * 업무별 상태 결정과 자원 반환은 같은 결과 반영 트랜잭션에서 처리한다.
     */
    public void applySuccessfulRefund(BigDecimal amount) {
        if (paidAmount == null
                || paidAmount.signum() < 0
                || amount == null
                || amount.signum() <= 0
                || amount.compareTo(paidAmount) > 0) {
            throw new CustomException(
                    ErrorCode.REGISTRATION_FINANCIAL_STATE_INVALID,
                    " 완료 환불 금액을 순납부액에 반영할 수 없습니다."
            );
        }

        this.paidAmount = paidAmount.subtract(amount);
    }
}
