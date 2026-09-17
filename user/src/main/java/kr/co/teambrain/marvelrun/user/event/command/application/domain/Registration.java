package kr.co.teambrain.marvelrun.user.event.command.application.domain;


import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.RegistrationBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.AddressBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationParticipantRequest;
import kr.co.teambrain.marvelrun.user.userinfo.command.application.domain.User;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GuardianBase;

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

    public static Registration createForPaymentMvp(
            Event event,
            EventCategory eventCategory,
            List<SouvenirJson> souvenirJsons,
            RegistrationCreateRequest request,
            BigDecimal contractAmount,
            LocalDateTime expiresAt
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
                .guardianName(guardianName)
                .guardianConsent(
                        Boolean.TRUE.equals(request.guardianConsent())
                )
                .contractAmount(contractAmount)
                .paidAmount(BigDecimal.ZERO)
                .status(RegistrationStatus.PAYMENT_PENDING)
                .expiresAt(expiresAt)
                .build();
    }

    /** 단체 신청에 따른 registration 구성 */
    public static Registration createForOrgPaymentMvp(
            Event event,
            EventCategory eventCategory,
            Organization organization,
            OrgRegistrationParticipantRequest request,
            List<SouvenirJson> souvenirJsons,
            BigDecimal contractAmount,
            LocalDateTime expiresAt
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
                .expiresAt(
                        expiresAt
                )

                .build();
    }

    /** 결제 성공금액 반영 */
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
}
