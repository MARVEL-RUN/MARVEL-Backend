package kr.co.teambrain.marvelrun.user.event.query.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GuardianBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;

/** 엔티티 대신 조회 컬럼을 담는 내부 프로젝션이다. Controller에서 직접 반환하지 않는다. */
public final class RegistrationQueryData {
    /** 내부 조회 타입의 이름 공간이다. */
    private RegistrationQueryData() { }

    /** 인증용 저장 비밀번호와 표시·수정에 필요한 현재 값을 포함하는 내부 행이다. */
    public record Member(String id, String name, String email, String birth, String phNum, String password,
            GenderClass gender, String categoryId, String categoryName,
            List<SouvenirJson> souvenirs, String address, String addressDetail,
            GuardianBase guardianBase, boolean guardianConsent, String guardianName, String guardianPhNum, String guardianRelationShip,
            RegistrationStatus status, BigDecimal contractAmount, BigDecimal paidAmount,
            boolean deleted, ReservationStatus reservationStatus, LocalDateTime paymentDeadline) { }

    /** 단체 인증·대표자 정보의 내부 행이다. 비밀번호를 응답으로 직렬화하지 않는다. */
    public record Organization(String id, String loginId, String password, String name,
            String leaderName, String birth, String phNum, String email,
            String address, String addressDetail, LocalDateTime paymentDeadline) { }

    /** 버튼 판단에 필요한 주문 컬럼만 읽는다. */
    public record Payment(String id, String registrationId, String orderId, BigDecimal amount,
            PaymentPurpose purpose, PaymentProcessStatus status) { }

    /** 주문 금액이 현재 신청에 대응하는지 확인할 최소 귀속값이다. */
    public record Allocation(String paymentId, String registrationId,
            BigDecimal amount, PaymentPurpose purpose) { }

    /** 환불 상태 표시·진행 중 차단을 위한 내부 값이다. */
    public record Refund(String paymentId, PaymentCancelStatus status) { }

    /** 선택된 기념품의 현재 표기명이다. */
    public record Souvenir(String id, String name) { }
}
