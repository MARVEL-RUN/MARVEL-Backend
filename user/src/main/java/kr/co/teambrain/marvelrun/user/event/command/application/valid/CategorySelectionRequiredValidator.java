package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationCreateRequest;

/** items 없는 개인 신청의 종목·기념품 누락을 필드 오류로 낸다. */
public class CategorySelectionRequiredValidator
        implements ConstraintValidator<CategorySelectionRequired, RegistrationCreateRequest> {

    @Override
    public boolean isValid(RegistrationCreateRequest request, ConstraintValidatorContext context) {
        if (request == null || request.hasItems()) {
            return true;
        }
        boolean categoryMissing = request.eventCategoryId() == null || request.eventCategoryId().isBlank();
        boolean souvenirMissing = request.selectedSouvenirList() == null || request.selectedSouvenirList().isEmpty();
        if (!categoryMissing && !souvenirMissing) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        if (categoryMissing) {
            context.buildConstraintViolationWithTemplate("{jakarta.validation.constraints.NotBlank.message}")
                    .addPropertyNode("eventCategoryId")
                    .addConstraintViolation();
        }
        if (souvenirMissing) {
            context.buildConstraintViolationWithTemplate("{jakarta.validation.constraints.NotEmpty.message}")
                    .addPropertyNode("selectedSouvenirList")
                    .addConstraintViolation();
        }
        return false;
    }
}
