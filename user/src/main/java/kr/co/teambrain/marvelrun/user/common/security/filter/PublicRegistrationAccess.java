package kr.co.teambrain.marvelrun.user.common.security.filter;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 본문 본인확인으로 개인 신청을 조회·관리하는 공개 API에 붙인다.
 * PublicRegistrationAccessInterceptor가 대회의 공개 조회·관리 허용 설정이 꺼져 있으면 거절한다.
 * 붙인 메서드의 경로에는 {eventId}가 있어야 한다. 없으면 설정과 관계없이 거절한다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicRegistrationAccess {
}
