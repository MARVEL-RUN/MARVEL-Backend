package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** 단체 계정 생성과 비밀번호 변경에 동일한 필수값·6~64자 정책을 적용한다. */
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE, RECORD_COMPONENT})
@Retention(RUNTIME)
@Constraint(validatedBy = {})
@NotBlank
@Size(min = 6, max = 64, message = "비밀번호는 6~64자여야 합니다.")
public @interface OrganizationRegistrationPassword {

    String message() default "단체 비밀번호 형식이 올바르지 않습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
