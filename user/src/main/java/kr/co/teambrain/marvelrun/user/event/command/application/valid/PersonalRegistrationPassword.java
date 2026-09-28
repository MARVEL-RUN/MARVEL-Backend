package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** 개인 신청 생성과 비밀번호 변경에 동일한 필수값·문자 수 정책을 적용한다. */
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE, RECORD_COMPONENT})
@Retention(RUNTIME)
@Constraint(validatedBy = {})
@NotBlank
@Size(max = 127)
public @interface PersonalRegistrationPassword {

    String message() default "개인 신청 비밀번호 형식이 올바르지 않습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
