package kr.co.teambrain.marvelrun.user.event.command.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationActionType;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationCapacityService;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.redis.RedisService;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.policy.EventRegistrationPolicy;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.ParticipantAddressModifyRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.ParticipantOrdererModifyRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.PersonalPasswordChangeRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationAccessVerifier;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationInformationPolicyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationPolicyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.dto.RegistrationPolicyInput;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventRegistrationPolicyRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyModels.Action;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyModels.RegistrationActionPolicyInput;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 로그인한 주문자의 마이페이지 수정: 주문 주소, 주문자 정보 일괄 변경, 비밀번호 일괄 변경.
 *
 * 주문자 정보와 비밀번호는 같은 주문자 키의 모든 주문(취소 포함)에 한 번에 넣는다. 한 주문만 바꾸면 주문자가 갈라진다.
 * 잠금 순서는 신청 생성과 같다(대회 → 신청).
 */
@Service
@RequiredArgsConstructor
public class ParticipantOrderModificationService {

    private final EntityManager entityManager;
    private final ServerTimeProvider time;
    private final RegistrationCommandRepository registrationRepository;
    private final EventRegistrationPolicyRepository eventPolicyRepository;
    private final RegistrationCapacityService registrationCapacityService;
    private final RegistrationInformationPolicyValidator informationPolicyValidator;
    private final RegistrationActionPolicyService actionPolicies;
    private final RegistrationPolicyValidator policyValidator;
    private final PasswordEncoder passwordEncoder;
    private final RedisService redisService;

    /**
     * 주문 하나의 주소만 바꾼다. 개인정보 수정과 같은 상태·수정 기간·차수 MODIFY 정책을 본다.
     * 정원·결제를 건드리지 않으므로 신청 행만 잠근다. 주소 필수 검사와 저장은 공개 개인정보 수정과 같다.
     */
    @Transactional
    public void modifyAddress(String registrationId, String ordererKey, ParticipantAddressModifyRequest request) {
        LocalDateTime now = time.currentDateTime();
        Registration registration = entityManager.find(Registration.class, registrationId, LockModeType.PESSIMISTIC_WRITE);
        if (registration == null || registration.isSoftDeleted() || !registration.isOwnedBy(ordererKey)) {
            throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED);
        }
        registration.validateOnlineRegistrationProcessingAllowed();
        validateModifiable(registration, now, loadModifyPolicies(registration.getEvent()));

        if (request.address() == null || request.address().isBlank()) {
            eventPolicyRepository.findByEventId(registration.getEvent().getId())
                    .ifPresent(policy -> policyValidator.validateAddress(policy, request.address()));
        }
        registration.changeAddress(request.address(), request.addressDetail());
    }

    /**
     * 주문자 정보(이름·연락처·생년월일·성별)를 모든 주문에 반영한다.
     * 살아 있는 주문마다 개인정보 수정과 같은 상태·기간·정책을 보고, 생년월일이 바뀌면 보호자 규칙을 다시 본다.
     * 살아 있는 주문이 없으면 바꾸지 않는다(취소 주문의 수정은 받지 않는다).
     * 신원을 바꿀 때만, 바꿀 신원을 다른 주문자가 살아 있는 주문으로 쓰는지 본다(개인 신청 수정과 같은 규칙).
     * 가격·정원은 다시 계산하지 않는다(상품 신청).
     */
    @Transactional
    public void modifyOrderer(String ordererKey, ParticipantOrdererModifyRequest request) {
        LocalDateTime now = time.currentDateTime();
        String eventId = eventIdOf(ordererKey);
        registrationCapacityService.lockEvent(eventId);
        List<Registration> orders = lockOrders(ordererKey);
        // 살아 있는 주문, 최근 신청 순이므로 첫 주문이 취소면 살아 있는 주문이 없다.
        Registration representative = orders.getFirst();
        if (representative.isSoftDeleted()) {
            throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED, " 살아 있는 주문이 없음");
        }

        Event event = representative.getEvent();
        List<RegistrationActionPolicyInput> policies = loadModifyPolicies(event);
        EventRegistrationPolicy eventPolicy = null;
        for (Registration order : orders) {
            if (order.isSoftDeleted()) {
                continue;
            }
            order.validateOnlineRegistrationProcessingAllowed();
            validateModifiable(order, now, policies);
            if (!Objects.equals(order.getBirth(), request.birth())) {
                // 일반 종목은 생년월일로 가격·정원을 다시 계산해야 하므로 이 경로로 바꾸지 않는다.
                if (!order.isProductPurchase()) {
                    throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET,
                            " 상품 신청이 아닌 주문의 생년월일 일괄 변경 registrationId=" + order.getId());
                }
                if (eventPolicy == null) {
                    eventPolicy = eventPolicyRepository.findByEventId(eventId)
                            .orElseThrow(() -> new CustomException(ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR));
                }
                policyValidator.validateParticipant(event, eventPolicy,
                        new RegistrationPolicyInput(request.birth(), order.getGuardianName(), order.isGuardianConsent()),
                        now.toLocalDate());
            }
        }

        boolean identityChanged = !Objects.equals(representative.getName(), request.name())
                || !Objects.equals(representative.getPhNum(), request.phNum())
                || !Objects.equals(representative.getBirth(), request.birth());
        // 대회 잠금 뒤 잠금 읽기로 다른 트랜잭션이 커밋한 최신 행까지 본다.
        if (identityChanged && registrationRepository.countOtherOrdererActiveByIdentityForShare(
                eventId, ordererKey, request.name(), request.phNum(), request.birth()) > 0) {
            throw new CustomException(ErrorCode.REGISTRATION_ALREADY_EXISTS);
        }

        orders.forEach(order -> order.changeOrdererIdentity(
                request.name(), request.phNum(), request.birth(), request.gender()));
    }

    /**
     * 비밀번호를 모든 주문(취소 포함)에 반영하고 refresh를 지운다. 이미 발급된 access는 만료까지 남는다.
     * 현재 비밀번호는 대표 주문(살아 있는 최근 주문, 없으면 최근 주문)의 해시로 확인한다.
     */
    @Transactional
    public void changePassword(String ordererKey, PersonalPasswordChangeRequest request) {
        // 추가 주문은 대회를 잠근 채 해시를 복사한다. 같은 순서(대회 → 주문)로 잠가 서로 다른 해시가 남지 않게 한다.
        registrationCapacityService.lockEvent(eventIdOf(ordererKey));
        List<Registration> orders = lockOrders(ordererKey);
        // 살아 있는 주문, 최근 신청 순이므로 첫 주문이 대표다.
        RegistrationAccessVerifier.verifyPersonalPassword(orders.getFirst(), request.currentPassword(), passwordEncoder);
        // BCrypt의 입력 한도를 넘는 원문은 자르지 않고 업무 오류로 거부한다.
        if (request.newPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new CustomException(ErrorCode.REGISTRATION_PASSWORD_TOO_LONG);
        }
        String encoded = passwordEncoder.encode(request.newPassword());
        orders.forEach(order -> order.changeOrdererPassword(encoded));
        deleteRefreshAfterCommit(ordererKey);
    }

    /**
     * 커밋 뒤에 refresh를 지운다. 커밋 전에 지우면 그 사이 옛 해시를 읽은 로그인이 refresh를 다시 저장할 수 있다.
     * 커밋 직전에 옛 해시로 확인한 로그인이 이 삭제 뒤에 refresh를 저장하는 좁은 경합은 남는다(변경 뒤 무효화는 후속).
     */
    private void deleteRefreshAfterCommit(String ordererKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            redisService.deleteParticipantRefreshToken(ordererKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisService.deleteParticipantRefreshToken(ordererKey);
            }
        });
    }

    /** 대회 id만 읽는다. 주문 엔티티를 미리 읽지 않아야 뒤의 잠금 조회가 최신 행을 준다. */
    private String eventIdOf(String ordererKey) {
        return registrationRepository.findEventIdByOrdererKey(ordererKey)
                .orElseThrow(() -> new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED, " 주문자의 주문이 없음"));
    }

    /** 주문자의 주문을 모두 잠근다. 살아 있는 주문, 최근 신청 순이다. */
    private List<Registration> lockOrders(String ordererKey) {
        List<Registration> orders = registrationRepository.findOrdererRegistrationsForUpdate(ordererKey);
        if (orders.isEmpty()) {
            throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED, " 주문자의 주문이 없음");
        }
        return orders;
    }

    private List<RegistrationActionPolicyInput> loadModifyPolicies(Event event) {
        return actionPolicies.loadEnabledRegistrationActionPolicies(event.getId(), RegistrationActionType.MODIFY);
    }

    /** 개인정보 수정과 같은 규칙: 신청 상태, 대회 수정 기간, 신청일이 속한 차수의 MODIFY 정책. */
    private void validateModifiable(Registration registration, LocalDateTime now,
                                    List<RegistrationActionPolicyInput> policies) {
        informationPolicyValidator.validateStatus(registration);
        informationPolicyValidator.validateRegistrationModificationPeriod(registration.getEvent(), now);
        actionPolicies.validateRegistrationActionPolicy(registration.getEvent(), registration, Action.MODIFY, now, policies);
    }
}
