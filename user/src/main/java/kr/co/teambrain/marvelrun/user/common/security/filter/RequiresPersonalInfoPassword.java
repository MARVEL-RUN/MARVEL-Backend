package kr.co.teambrain.marvelrun.user.common.security.filter;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이름·생년월일·휴대폰과 비밀번호로 신청을 찾는 기존 API에 붙인다.
 * ParticipantAuthMethodInterceptor가 이 표시가 있는 API만 대회 인증 방식으로 검사한다.
 *
 * 붙인 메서드의 경로에는 {eventId}가 있어야 한다. 없으면 검사하지 않고 통과한다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPersonalInfoPassword {
}
