package kr.co.teambrain.marvelrun.admin.event.command.application.service;

import kr.co.teambrain.marvelrun.admin.common.dto.request.PasswordResetRequest;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.ReservationItem;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.AdminRegistrationModifyRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.RegistrationDeleteResponse;
import kr.co.teambrain.marvelrun.admin.event.command.repository.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RegistrationCommandService {

    private final RegistrationCommandRepository registrationCommandRepository;
    private final ReservationCommandRepository reservationCommandRepository;
    private final ReservationItemCommandRepository reservationItemCommandRepository;
    private final PaymentCommandRepository paymentCommandRepository;
    private final PaymentAllocationCommandRepository paymentAllocationCommandRepository;
    private final CapacityCommandRepository capacityCommandRepository;
    private final SouvenirCommandRepository souvenirCommandRepository;

    /**
     * 개인 신청 비밀번호 초기화
     */
    public void resetPersonalPassword(String registrationId, PasswordResetRequest request) {
        Registration registration = registrationCommandRepository.findById(registrationId)
                .orElseThrow(() -> new CustomException(ErrorCode.REGISTRATION_NOT_FOUND));

        if (registration.getOrganization() != null) {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET);
        }

        if (request.newPassword().length() < 6) {
            throw new CustomException(ErrorCode.INVALID_PASSWORD_LENGTH);
        }

        registration.resetPasswordByAdmin(request.newPassword());
    }

    public void modifyRegistrationBasicInfo(String registrationId, AdminRegistrationModifyRequest request) {
        Registration registration = registrationCommandRepository.findById(registrationId)
                .orElseThrow(() -> new CustomException(ErrorCode.REGISTRATION_NOT_FOUND));

        LocalDate modifiedBirth = LocalDate.parse(request.birth(), DateTimeFormatter.ISO_DATE);
        LocalDate eventDate = registration.getEvent().getStartDate().toLocalDate();

        // 1. 10Km 코스 연령 제한 방어 (만 12세 이하 - 2013년 11월 1일 이후 출생자)
        LocalDate childCutoff = LocalDate.of(2013, 11, 1);
        boolean isChildRule = !modifiedBirth.isBefore(childCutoff);

        if (isChildRule && registration.getEventCategory().getName().contains("10")) {
            throw new CustomException(ErrorCode.AGE_RESTRICTION_VIOLATION);
        }

        // 2. 보호자 정보 누락 방어
        if (isChildRule && (request.guardianName() == null || request.guardianName().isBlank())) {
            throw new CustomException(ErrorCode.GUARDIAN_INFO_REQUIRED);
        }

        // 3. 결제 금액 변동(요금제 변경) 차단 방어 (대회일 기준 만 13세 미만 여부로 판별)
        LocalDate oldBirth = LocalDate.parse(registration.getBirth(), DateTimeFormatter.ISO_DATE);
        boolean wasChildPrice = eventDate.isBefore(oldBirth.plusYears(13));
        boolean willBeChildPrice = eventDate.isBefore(modifiedBirth.plusYears(13));

        if (wasChildPrice != willBeChildPrice) {
            throw new CustomException(ErrorCode.PRICE_TIER_CHANGE_NOT_ALLOWED);
        }

        // 4. 복합 유니크(이름+연락처+생년월일) 중복 방어
        if (registrationCommandRepository.existsOtherActiveByEventIdAndUniqueInfo(
                registration.getEvent().getId(),
                registration.getId(),
                request.name(),
                request.phNum(),
                request.birth())) {
            throw new CustomException(ErrorCode.DUPLICATE_REGISTRATION);
        }

        registration.modifyBasicInfoByAdmin(
                request.name(),
                request.phNum(),
                request.email(),
                request.birth(),
                request.gender(),
                request.address(),
                request.addressDetail(),
                request.guardianName(),
                request.guardianPhNum(),
                request.guardianRelationship(),
                LocalDateTime.now()
        );
    }

    @Transactional
    public RegistrationDeleteResponse deletePaymentPendingRegistration(String registrationId) {
        Registration registration = registrationCommandRepository.findById(registrationId)
                .orElseThrow(() -> new CustomException(ErrorCode.REGISTRATION_NOT_FOUND));

        // 1. 상태 검증: '결제 대기(PAYMENT_PENDING)' 상태만 삭제 허용
        if (registration.getStatus() != RegistrationStatus.PAYMENT_PENDING) {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET);
        }

        String courseName = registration.getEventCategory().getName();
        List<String> souvenirDetails = new ArrayList<>();

        if (registration.getSouvenirJson() != null) {
            for (kr.co.teambrain.marvelrun.common.json_object.SouvenirJson sJson : registration.getSouvenirJson()) {
                souvenirCommandRepository.findById(sJson.souvenirId()).ifPresent(souvenir -> {
                    String sizeText = sJson.selectedSize() != null && !sJson.selectedSize().isBlank()
                            ? "(" + sJson.selectedSize() + ")" : "";
                    souvenirDetails.add(souvenir.getName() + sizeText);
                });
            }
        }

        String souvenirMessage = souvenirDetails.isEmpty() ? "선택된 기념품 없음" : String.join(", ", souvenirDetails);
        String resultMessage = String.format("삭제 완료: [%s] 코스 및 [%s] 정원이 확보되었습니다.", courseName, souvenirMessage);

        // 2. Registration 소프트 삭제 및 EXPIRED 상태 처리
        registration.expireByAdmin(LocalDateTime.now());

        // 3. Reservation 상태 RELEASED 변경 및 Capacity 자원 복구
        reservationCommandRepository.findByRegistration_Id(registrationId).ifPresent(reservation -> {
            if (reservation.getStatus() != ReservationStatus.RELEASED) {
                reservation.releaseByAdmin();

                List<ReservationItem> items = reservationItemCommandRepository.findAllByReservation_Id(reservation.getId());
                for (ReservationItem item : items) {
                    // Capacity의 heldCount(임시 점유) 수량 반환
                    capacityCommandRepository.decreaseHeldCount(item.getCapacity().getId(), item.getQuantity());
                }
            }
        });

        // 4. PaymentAllocation (결제 귀속) 내역 삭제
        List<PaymentAllocation> allocations = paymentAllocationCommandRepository.findAllByRegistration_Id(registrationId);
        if (!allocations.isEmpty()) {
            paymentAllocationCommandRepository.deleteAll(allocations);
        }

        // 5. Payment (결제 원본) 삭제
        List<Payment> payments = paymentCommandRepository.findAllByRegistration_Id(registrationId);
        for (Payment payment : payments) {
            // 단체 결제 등의 이유로 동일 Payment에 다른 사람의 Allocation이 얽혀있다면 Payment 자체는 지우지 않음
            boolean hasOtherAllocations = paymentAllocationCommandRepository.existsByPayment_Id(payment.getId());
            if (!hasOtherAllocations) {
                paymentCommandRepository.delete(payment);
            }
        }

        return new RegistrationDeleteResponse(resultMessage);
    }
}
