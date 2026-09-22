package kr.co.teambrain.marvelrun.user.event.command.application.domain;


import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.RegistrationBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.AddressBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GuardianBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationModificationParticipantRequest;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationParticipantRequest;
import kr.co.teambrain.marvelrun.user.userinfo.command.application.domain.User;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** 신청의 참가 정보와 금융 요약을 관리하며 개인정보 정정은 허용 필드에만 반영한다. */
@org.hibernate.annotations.DynamicUpdate
@Getter
@Entity
@SuperBuilder
@Table(name = "registration")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Registration extends RegistrationBase<
        User,
        Event,
        EventCategory,
        Organization,
        Souvenir
        > {

    /**
     * 개인 신청의 기본정보와 보호자 정보를 공통 반영한다.
     * 종목·생년월일·기념품·금융 정보는 변경하지 않는다.
     */
    public void applyPersonalInformation(RegistrationModificationRequest request) {
        if (organization != null || softDeleted) {
            throw new CustomException(
                    ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET
            );
        }

        if (request == null) {
            throw new CustomException(
                    ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT
            );
        }

        boolean requestedConsent = request.guardianConsent();

        // 기존 동의 철회 차단
        if (this.guardianConsent && !requestedConsent) {
            throw new CustomException(ErrorCode.GUARDIAN_CONSENT_REQUIRED);
        }

        String normalizedGuardianName = request.guardianName();
        if (normalizedGuardianName != null) {
            normalizedGuardianName = normalizedGuardianName.strip();

            if (normalizedGuardianName.isEmpty()) {
                normalizedGuardianName = null;
            }
        }

        this.name = request.name();
        this.phNum = request.phNum();
        this.gender = request.gender();
        this.address = request.address();
        this.addressDetail = request.addressDetail();

        this.guardianName = normalizedGuardianName;
        this.guardianPhNum = request.guardianPhNum();
        this.guardianRelationship = request.guardianRelationship();
        this.email = request.email();

        // 최초 동의 반영: 트랜잭션 커밋 시 저장
        if (!this.guardianConsent && requestedConsent) {
            this.guardianConsent = true;
        }
    }

    public static Registration createForPaymentMvp(
            Event event,
            EventCategory eventCategory,
            List<SouvenirJson> souvenirJsons,
            RegistrationCreateRequest request,
            BigDecimal contractAmount,
            LocalDateTime now // 추가됨
    ) {

        String guardianName = request.guardianName();

        if (guardianName != null) {
            guardianName = guardianName.strip();

            if (guardianName.isEmpty()) {
                guardianName = null;
            }
        }

        return Registration.builder()
                .user(null)
                .event(event)
                .eventCategory(eventCategory)
                .souvenirJson(souvenirJsons)
                .password(request.password())
                .name(request.name())
                .phNum(request.phNum())
                .birth(request.birth())
                .gender(request.gender())
                .address(request.address())
                .addressDetail(request.addressDetail())
                .guardianRelationship(request.guardianRelationship())
                .guardianPhNum(request.guardianPhNum())
                .guardianName(guardianName)
                .guardianConsent(
                        Boolean.TRUE.equals(request.guardianConsent())
                )
                .contractAmount(contractAmount)
                .paidAmount(BigDecimal.ZERO)
                .status(RegistrationStatus.PAYMENT_PENDING)
                .termsEssentialAgreed(request.termsEssentialAgreed()) // 추가됨
                .termsMarketingAgreed(request.termsMarketingAgreed()) // 추가됨
                .termsMarketingChannelAgreed(request.termsMarketingChannelAgreed()) // 추가됨
                .termsAgreedAt(now) // 추가됨
                .email(request.email())
                .build();
    }

    /**
     * 단체 신청에 따른 registration 구성
     */
    public static Registration createForOrgPaymentMvp(
            Event event,
            EventCategory eventCategory,
            Organization organization,
            OrgRegistrationParticipantRequest request,
            List<SouvenirJson> souvenirJsons,
            BigDecimal contractAmount,
            LocalDateTime now,                  // 추가됨: 약관 동의 일시
            boolean termsEssentialAgreed,       // 추가됨: 필수 약관 동의
            boolean termsMarketingAgreed,       // 추가됨: 마케팅 동의
            boolean termsMarketingChannelAgreed, // 추가됨: 전자적 매체 수신 동의
            String email
    ) {

        return Registration.builder()
                .event(
                        event
                )
                .eventCategory(
                        eventCategory
                )
                .organization(
                        organization
                )
                .user(
                        null
                )
                .souvenirJson(
                        souvenirJsons
                )

                /*
                 * 단체 신청으로 생성된 Registration은
                 * 소유신청 이전에는 신청자 본인이 직접 접근하지 않는다.
                 *
                 * MVP에서는 난수 생성 로직을 생략하고
                 * 임시 하드코딩 비밀번호를 사용한다.
                 */
                .password(
                        "%^MVP_ORG_T&*EM^&#P_PA$%SSWO@!RD"
                )

                .name(
                        request.name()
                )
                .phNum(
                        request.phNum()
                )
                .birth(
                        request.birth()
                )
                .gender(
                        request.gender()
                )

                /*
                 * 단체 신청 참가자는 Organization의 주소를 사용한다.
                 *
                 * 따라서 Registration 자체 주소는 저장하지 않는다.
                 */
                .address(
                        null
                )
                .addressDetail(
                        null
                )
                .addressBase(
                        AddressBase.ORGANIZATION
                )

                /*
                 * 단체 신청 참가자의 보호자 정보는 Organization에서 참조한다.
                 *
                 * 이름은 leaderName,
                 * 연락처는 leaderPhNum,
                 * 동의 여부는 Organization.guardianConsent를 사용한다.
                 *
                 * Registration의 개별 보호자 필드에는 복사하지 않는다.
                 */
                .guardianBase(
                        GuardianBase.ORG_LEADER
                )

                .status(
                        RegistrationStatus.PAYMENT_PENDING
                )
                .contractAmount(
                        contractAmount
                )
                .paidAmount(
                        BigDecimal.ZERO
                )
                // --- 새롭게 추가된 약관 동의 매핑 ---
                .termsEssentialAgreed(
                        termsEssentialAgreed
                )
                .termsMarketingAgreed(
                        termsMarketingAgreed
                )
                .termsMarketingChannelAgreed(
                        termsMarketingChannelAgreed
                )
                .termsAgreedAt(
                        now
                ).
                email(
                        email
                )
                .build();
    }

    /**
     * 결제 성공금액 반영
     */
    public void applySuccessfulPayment(
            BigDecimal amount
    ) {

        this.paidAmount =
                this.paidAmount.add(amount);

        int comparison =
                this.paidAmount.compareTo(
                        this.contractAmount
                );

        if (comparison == 0) {

            this.status =
                    RegistrationStatus.CONFIRMED;

            return;
        }

        if (comparison < 0) {

            this.status =
                    RegistrationStatus
                            .ADDITIONAL_PAYMENT_REQUIRED;

            return;
        }

        /*
         * paidAmount > contractAmount는
         * 향후 가격변경/환불 정책에서 별도 관리.
         *
         * Toss 승인 자체는 이미 발생했으므로 여기서
         * RuntimeException을 던져 Transaction을 rollback하면 안 된다.
         */
        this.status =
                RegistrationStatus.CONFIRMED;
    }

    /**
     * 정책검증과 Capacity 이동이 완료된 개인 수정 후보를 반영한다.
     *
     * 종목과 기념품은 Validator가 검증·정규화한 값을 사용한다.
     * 계약금액은 서버 Pricing 결과를 사용한다.
     *
     * 비밀번호·실제 순결제금액·금융 상태는 이 메서드에서 변경하지 않는다.
     * 금융 상태 결정은 같은 수정 트랜잭션의 후속 단계가 담당한다.
     */
    public void applyPersonalModification(
            EventCategory validatedCategory,
            List<SouvenirJson> validatedSouvenirs,
            RegistrationModificationRequest request,
            BigDecimal newContractAmount
    ) {
        if (organization != null || softDeleted) {
            throw new CustomException(
                    ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET,
                    " 활성 개인 신청만 수정할 수 있습니다."
            );
        }

        if (request == null) {
            throw new CustomException(
                    ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT,
                    " 개인 신청 수정 요청이 없습니다."
            );
        }

        validateModificationArguments(
                validatedCategory,
                validatedSouvenirs,
                newContractAmount
        );

        /*
         * 필드 변경 전에 복사와 정규화를 완료한다.
         * 요청의 원본 기념품 목록을 직접 저장하지 않는다.
         */
        List<SouvenirJson> souvenirs =
                List.copyOf(validatedSouvenirs);

        applyPersonalInformation(request);

        this.eventCategory = validatedCategory;
        this.souvenirJson = souvenirs;
        this.birth = request.birth();
        this.contractAmount = newContractAmount;
    }

    /**
     * 검증과 Capacity 이동을 마친 기존 단체 구성원의 후보를 반영한다.
     *
     * 단체 귀속·주소 참조·보호자 참조·비밀번호는 유지한다.
     * 실제 순결제금액과 금융 상태는 후속 금융 처리에서 관리한다.
     */
    public void applyOrganizationModification(
            EventCategory validatedCategory,
            List<SouvenirJson> validatedSouvenirs,
            OrgRegistrationModificationParticipantRequest request,
            BigDecimal newContractAmount
    ) {
        if (organization == null || softDeleted) {
            throw new CustomException(
                    ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET,
                    " 활성 단체 구성원만 수정할 수 있습니다."
            );
        }

        if (request == null) {
            throw new CustomException(
                    ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT,
                    " 단체 구성원 수정 요청이 없습니다."
            );
        }

        if (id == null || !Objects.equals(id, request.registrationId())) {
            throw new CustomException(
                    ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET,
                    " 수정 요청과 Registration 식별자가 일치하지 않습니다."
            );
        }

        validateModificationArguments(
                validatedCategory,
                validatedSouvenirs,
                newContractAmount
        );

        List<SouvenirJson> souvenirs =
                List.copyOf(validatedSouvenirs);

        this.eventCategory = validatedCategory;
        this.souvenirJson = souvenirs;

        this.name = request.name();
        this.phNum = request.phNum();
        this.birth = request.birth();
        this.gender = request.gender();

        this.contractAmount = newContractAmount;
        this.email = request.email();
    }

    /** 기존 단체 신청의 개인정보만 정정하며 정책·금융·귀속 필드는 요청값으로 덮어쓰지 않는다. */
    public void applyOrganizationPersonalInformation(OrgRegistrationModificationParticipantRequest request) {
        if (organization == null || softDeleted || id == null || request == null
                || !Objects.equals(id, request.registrationId())) {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET);
        }
        this.name = request.name();
        this.phNum = request.phNum();
        this.gender = request.gender();
        this.email = request.email();
    }

    /** 단체 일부 제거의 기존 호출 계약을 유지하며 공통 참가 취소 상태 전이를 사용한다. */
    public void removeFromOrganization() {
        if (organization == null || softDeleted) {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET,
                    "활성 단체 구성원만 제거할 수 있습니다.");
        }
        cancelParticipation();
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

    /**
     * 실제 Entity 반영에 필요한 후보값과 계약금액을 확인한다.
     *
     * 상세 정책검증을 대신하지 않으며,
     * 내부 호출의 필수 값 누락과 잘못된 금액을 차단한다.
     */
    private void validateModificationArguments(
            EventCategory validatedCategory,
            List<SouvenirJson> validatedSouvenirs,
            BigDecimal newContractAmount
    ) {
        if (validatedCategory == null
                || validatedSouvenirs == null
                || validatedSouvenirs.stream().anyMatch(Objects::isNull)
                || newContractAmount == null
                || newContractAmount.signum() < 0) {
            throw new CustomException(
                    ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT
            );
        }
    }


}
