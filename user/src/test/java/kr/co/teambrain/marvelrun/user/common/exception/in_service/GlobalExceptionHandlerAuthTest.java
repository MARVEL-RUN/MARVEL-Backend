package kr.co.teambrain.marvelrun.user.common.exception.in_service;

import kr.co.teambrain.marvelrun.user.common.exception.JwtAuthenticationException;
import kr.co.teambrain.marvelrun.user.common.security.details.CustomAdminDetail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/** 권한 거부와 서비스의 토큰 오류가 500이 아니라 401·403으로 나가는지 확인한다. */
class GlobalExceptionHandlerAuthTest {

    final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/participant/auth/logout");

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 익명_사용자의_권한_거부는_401이다() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        ResponseEntity<?> response = handler.handleAccessDenied(new AccessDeniedException("x"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(((AuthErrorResponse) response.getBody()).getCode()).isEqualTo(AuthErrorCode.UNAUTHORIZED);
    }

    @Test
    void 인증_정보가_없어도_401이다() {
        ResponseEntity<?> response = handler.handleAccessDenied(new AccessDeniedException("x"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void 로그인한_관리자의_권한_거부는_403_ACCESS_DENIED다() {
        CustomAdminDetail admin = new CustomAdminDetail("admin-1", "관리자", "ADMIN");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, "", admin.getAuthorities()));

        ResponseEntity<?> response = handler.handleAccessDenied(new AccessDeniedException("x"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(((ErrorResponse) response.getBody()).getCode()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void 서비스의_토큰_오류는_401_AuthErrorResponse다() {
        ResponseEntity<AuthErrorResponse> response = handler.handleJwtAuthenticationException(
                new JwtAuthenticationException(AuthErrorCode.INVALID_REFRESH_TOKEN), request);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody().getCode()).isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);
        assertThat(response.getBody().isCanRefresh()).isFalse();
    }
}
