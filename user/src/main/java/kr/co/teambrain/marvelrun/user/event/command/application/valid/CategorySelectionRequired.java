package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * 상품 구매 줄(items)이 없는 개인 신청은 최상위 종목과 기념품이 있어야 한다.
 *
 * items가 생기기 전 @NotBlank·@NotEmpty와 같은 오류를 낸다. 오류는 eventCategoryId·selectedSouvenirList 필드에 붙고
 * 문구도 같은 기본 문구를 쓴다. 응답 코드는 GlobalExceptionHandler가 REQUIRED로 바꾼다.
 */
@Target(TYPE)
@Retention(RUNTIME)
@Constraint(validatedBy = CategorySelectionRequiredValidator.class)
public @interface CategorySelectionRequired {

    String message() default "{jakarta.validation.constraints.NotBlank.message}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
