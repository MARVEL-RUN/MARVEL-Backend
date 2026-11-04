package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationPolicyValidator;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventRegistrationPolicyRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationEntryCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개인 수정에서 신청 정책(주소 필수)과 상품 종류(참가 항목)를 지킨다.
 *
 * 참가 항목이 있는 상품은 개인 수정으로 종목을 바꾸지 않는다. 상향은 전용 흐름이 맡고,
 * 그 밖의 상품 변경은 취소 뒤 다시 신청한다.
 * 단품의 참가 항목은 신청자 값을 따르므로 신청자 정보가 바뀌면 함께 바꾼다.
 * 패키지 받는 사람 수정은 별도 흐름이 맡는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RegistrationProductModificationService {

    private final EventRegistrationPolicyRepository eventRegistrationPolicyRepository;
    private final EventCategoryCommandRepository eventCategoryCommandRepository;
    private final RegistrationEntryCommandRepository registrationEntryCommandRepository;
    private final RegistrationPolicyValidator registrationPolicyValidator;

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
        if (currentCategory.getId().equals(request.eventCategoryId())) {
            return;
        }

        boolean targetHasProduct = eventCategoryCommandRepository.findById(request.eventCategoryId())
                .map(category -> category.getProductType() != null)
                .orElse(false);
        if (currentCategory.getProductType() != null || targetHasProduct) {
            throw new CustomException(
                    ErrorCode.REGISTRATION_PRODUCT_CHANGE_NOT_ALLOWED,
                    " registrationId=" + current.getId()
            );
        }
    }

    /** 수정 반영 뒤 단품의 참가 항목을 바뀐 신청자 값으로 맞춘다. */
    public void syncApplicantEntry(Registration current, RegistrationModificationRequest request) {
        if (current.getEventCategory().getProductType() != ProductType.SINGLE) {
            return;
        }

        for (RegistrationEntry entry : registrationEntryCommandRepository.findByRegistration_Id(current.getId())) {
            entry.updateRecipient(request.name(), request.phNum(), request.birth(), request.gender());
        }
    }
}
