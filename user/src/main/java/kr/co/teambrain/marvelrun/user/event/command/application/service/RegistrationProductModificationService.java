package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.policy.EventRegistrationPolicy;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationPolicyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.dto.RegistrationPolicyInput;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventRegistrationPolicyRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationEntryCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 개인 수정에서 신청 정책(주소 필수)과 상품 종류(참가 항목)를 지킨다.
 *
 * 참가 항목이 있는 상품 신청은 개인 수정으로 상품·기념품을 바꾸지 않는다. 상향은 전용 흐름이 맡고,
 * 그 밖의 상품 변경은 취소 뒤 다시 신청한다.
 * 상품 신청의 신청 행 종목·기념품은 첫 구매 줄의 대표값이므로 가격·정원을 다시 계산하지 않는다.
 * 신청자 정보가 바뀌어도 참가 항목(받는 사람)은 바꾸지 않는다. 받는 사람 수정은 별도 흐름이 맡는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RegistrationProductModificationService {

    private final EventRegistrationPolicyRepository eventRegistrationPolicyRepository;
    private final EventCategoryCommandRepository eventCategoryCommandRepository;
    private final RegistrationEntryCommandRepository registrationEntryCommandRepository;
    private final RegistrationPolicyValidator registrationPolicyValidator;
    private final RegistrationModificationClassifier classifier;

    /**
     * 수정 반영 전에 주소 필수 정책과 상품 종목 변경 제한을 검사한다.
     *
     * 주소가 있으면 정책과 무관하게 통과하므로, 주소가 빌 때만 정책을 읽는다.
     * 개인정보만 바꾸는 수정이 정책 테이블을 읽지 않는 기존 경계를 지킨다.
     */
    public void validate(Registration current, RegistrationModificationRequest request) {
        if (request.address() == null || request.address().isBlank()) {
            eventRegistrationPolicyRepository.findByEventId(current.getEvent().getId())
                    .ifPresent(policy -> registrationPolicyValidator.validateAddress(policy, request.address()));
        }

        EventCategory currentCategory = current.getEventCategory();
        // 상품 신청은 같은 종목이어도 기념품을 바꾸면 대표 줄 하나로 정원을 다시 계산하게 되므로 막는다.
        if (current.isProductPurchase() && classifier.selectionChanged(current, request)) {
            throw new CustomException(
                    ErrorCode.REGISTRATION_PRODUCT_CHANGE_NOT_ALLOWED,
                    " registrationId=" + current.getId()
            );
        }
        if (currentCategory.getId().equals(request.eventCategoryId())) {
            return;
        }

        // 다른 대회의 종목은 여기서 판정하지 않고 뒤의 기존 종목 검증이 거절하게 둔다.
        boolean targetHasProduct = eventCategoryCommandRepository.findById(request.eventCategoryId())
                .filter(category -> category.getEvent().getId().equals(current.getEvent().getId()))
                .map(category -> category.getProductType() != null)
                .orElse(false);
        if (targetHasProduct) {
            throw new CustomException(
                    ErrorCode.REGISTRATION_PRODUCT_CHANGE_NOT_ALLOWED,
                    " registrationId=" + current.getId()
            );
        }
    }

    /**
     * 상품 신청의 신청자 생년월일·보호자 변경을 개인정보 수정으로 처리해도 되는지 본다.
     *
     * 생년월일이나 보호자 입력이 바뀌면 보호자 정책을 신청자와 모든 참가 항목에 다시 적용한다.
     * 보호자는 신청에 하나이고 받는 사람 모두가 같이 쓰기 때문이다.
     * 신청 행은 바꾸지 않는다. 생년월일 반영은 개인정보 수정 서비스가 한다. 가격·정원·참가 항목은 바꾸지 않는다.
     */
    public void validateApplicantChange(
            Registration current,
            RegistrationModificationRequest request,
            LocalDate applicationDate
    ) {
        String guardianName = RegistrationModificationClassifier.normalizeGuardianName(request.guardianName());
        boolean birthChanged = !Objects.equals(current.getBirth(), request.birth());
        boolean guardianChanged = !Objects.equals(current.getGuardianName(), guardianName)
                || current.isGuardianConsent() != request.guardianConsent();
        if (!birthChanged && !guardianChanged) {
            return;
        }

        EventRegistrationPolicy policy = eventRegistrationPolicyRepository.findByEventId(current.getEvent().getId())
                .orElseThrow(() -> new CustomException(ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR));
        registrationPolicyValidator.validateParticipant(current.getEvent(), policy,
                new RegistrationPolicyInput(request.birth(), guardianName, request.guardianConsent()), applicationDate);
        for (RegistrationEntry entry : registrationEntryCommandRepository.findByRegistration_Id(current.getId())) {
            registrationPolicyValidator.validateParticipant(current.getEvent(), policy,
                    new RegistrationPolicyInput(entry.getBirth(), guardianName, request.guardianConsent()),
                    applicationDate);
        }
    }
}
