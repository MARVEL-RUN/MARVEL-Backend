package kr.co.teambrain.marvelrun.admin.event.command.application.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import kr.co.teambrain.marvelrun.admin.common.dto.request.PasswordResetRequest;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.AdminRegistrationModifyRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.RegistrationDeleteResponse;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.UnpaidRegistrationBatchRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.UnpaidRegistrationBatchResponse;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.UnpaidRegistrationBatchResponse.Failure;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.UnpaidRegistrationBatchResponse.Success;
import kr.co.teambrain.marvelrun.admin.event.command.repository.RegistrationCommandRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 관리자 신청 변경·비밀번호 초기화와 미결제 삭제의 건별 결과 수집을 수행한다. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RegistrationCommandService {
    private static final int MAX_UNPAID_CANCELLATION_COUNT = 50;

    private final PasswordEncoder passwordEncoder;

    private final RegistrationCommandRepository registrationCommandRepository;
    private final AdminUnpaidRegistrationCancellationService unpaidCancellation;

    /**
     * 개인 신청 비밀번호를 해시로 변환하여 초기화한다.
     */
    public void resetPersonalPassword(String registrationId, PasswordResetRequest request) {
        Registration registration = registrationCommandRepository.findById(registrationId)
                .orElseThrow(() -> new CustomException(ErrorCode.REGISTRATION_NOT_FOUND));

        if (registration.getOrganization() != null) {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET);
        }

        if (request.newPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new CustomException(ErrorCode.REGISTRATION_PASSWORD_TOO_LONG);
        }
        if (request.newPassword().length() < 6) {
            throw new CustomException(ErrorCode.INVALID_PASSWORD_LENGTH);
        }

        registration.resetPasswordByAdmin(passwordEncoder.encode(request.newPassword()));
    }

    /** 기본정보를 정정하되 이미 결제한 신청의 가격 구분 변경을 차단한다. */
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

        // 2026-10-03 외부 접수는 2013-11-01 포함 기준이다. 다른 기준의 외부 접수 재사용 시 반드시 재검토한다.
        if (registration.isExternalPayment()) {
            wasChildPrice = !oldBirth.isBefore(childCutoff);
            willBeChildPrice = !modifiedBirth.isBefore(childCutoff);
        }

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

    /** 미결제 취소의 금융 잠금·주문 무효화·자원 반환을 하나의 트랜잭션으로 위임한다. */
    @Transactional
    public RegistrationDeleteResponse deletePaymentPendingRegistration(String registrationId) {
        return unpaidCancellation.cancel(registrationId);
    }

    /**
     * 입력 순서대로 중복 없는 신청을 취소하고 건별 커밋·롤백 이후 결과를 수집한다.
     * 호출자의 트랜잭션은 중단하여 한 건의 실패가 다른 건을 롤백하지 않도록 한다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UnpaidRegistrationBatchResponse cancelUnpaidRegistrations(
            String eventId,
            UnpaidRegistrationBatchRequest request
    ) {
        validateUnpaidCancellationRequest(eventId, request);

        Set<String> registrationIds = new LinkedHashSet<>(request.registrationIds());
        List<Success> successes = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();

        for (String registrationId : registrationIds) {
            try {
                Success success = unpaidCancellation.cancelUnpaidRegistrationInEvent(
                        eventId,
                        registrationId
                );

                successes.add(success);
            } catch (CustomException exception) {
                failures.add(collectUnpaidCancellationFailure(eventId, registrationId, exception));
            } catch (RuntimeException exception) {
                log.error(
                        "미결제 신청 삭제 오류: eventId={}, registrationId={}, exceptionType={}",
                        eventId,
                        registrationId,
                        exception.getClass().getName()
                );

                CustomException failure = new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, exception);
                failures.add(collectUnpaidCancellationFailure(eventId, registrationId, failure));
            }
        }

        return new UnpaidRegistrationBatchResponse(
                request.registrationIds().size(),
                registrationIds.size(),
                successes.size(),
                failures.size(),
                successes,
                failures
        );
    }

    /** 중복 제거 전에 원본 요청 전체를 검증하여 잘못된 입력의 일부 실행을 방지한다. */
    private void validateUnpaidCancellationRequest(
            String eventId,
            UnpaidRegistrationBatchRequest request
    ) {
        if (eventId == null || eventId.isBlank() || request == null) {
            throw new CustomException(ErrorCode.INVALID_UNPAID_CANCELLATION_REQUEST);
        }

        List<String> registrationIds = request.registrationIds();

        if (registrationIds == null
                || registrationIds.isEmpty()
                || registrationIds.size() > MAX_UNPAID_CANCELLATION_COUNT) {
            throw new CustomException(ErrorCode.INVALID_UNPAID_CANCELLATION_REQUEST);
        }

        for (String registrationId : registrationIds) {
            if (registrationId == null || registrationId.isBlank()) {
                throw new CustomException(ErrorCode.INVALID_UNPAID_CANCELLATION_REQUEST);
            }
        }
    }

    /** 취소 롤백 이후 별도로 정보를 조회하며 조회 실패도 원래 실패 결과를 덮어쓰지 않는다. */
    private Failure collectUnpaidCancellationFailure(
            String eventId,
            String registrationId,
            CustomException failure
    ) {
        ErrorCode errorCode = failure.getErrorCode();

        try {
            return unpaidCancellation.loadCancellationFailure(eventId, registrationId, errorCode);
        } catch (RuntimeException exception) {
            log.warn(
                    "미결제 삭제 실패 정보 조회 오류: eventId={}, registrationId={}, code={}, exceptionType={}",
                    eventId,
                    registrationId,
                    errorCode.name(),
                    exception.getClass().getName()
            );

            return Failure.withoutRegistrationDetails(registrationId, errorCode);
        }
    }
}
