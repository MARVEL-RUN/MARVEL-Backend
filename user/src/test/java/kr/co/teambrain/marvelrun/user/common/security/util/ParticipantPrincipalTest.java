package kr.co.teambrain.marvelrun.user.common.security.util;

import kr.co.teambrain.marvelrun.user.common.security.details.CustomAdminDetail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParticipantPrincipalTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 참가자면_신청_id를_돌려준다() {
        authenticate("registration-1", "PARTICIPANT");

        assertThat(ParticipantPrincipal.registrationId()).isEqualTo("registration-1");
    }

    @Test
    void 관리자면_거부한다() {
        authenticate("admin-1", "ADMIN");

        assertThatThrownBy(ParticipantPrincipal::registrationId).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void 인증이_없으면_거부한다() {
        assertThatThrownBy(ParticipantPrincipal::registrationId).isInstanceOf(AccessDeniedException.class);
    }

    private static void authenticate(String id, String role) {
        CustomAdminDetail detail = new CustomAdminDetail(id, "이름", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(detail, "", detail.getAuthorities()));
    }
}
