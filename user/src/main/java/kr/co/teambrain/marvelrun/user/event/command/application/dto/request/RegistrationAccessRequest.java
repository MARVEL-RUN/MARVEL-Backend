package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;


import jakarta.validation.constraints.NotBlank;

/**
 * 개인 Registration 수정 요청마다 전달되는 본인확인 입력값이다.
 *
 * 이전 신청 조회 과정에서 동일한 비밀번호를 이미 검증했더라도
 * 그 인증 결과를 수정 권한으로 재사용하지 않는다.
 *
 * 별도의 Cookie / Session / Redis 임시 토큰을 발급하지 않으므로,
 * 실제 수정 요청에서도 이 정보를 다시 전달받아
 * 현재 DB의 Registration 정보와 재검증한다.
 */
public record RegistrationAccessRequest(
        @NotBlank String name,
        @NotBlank String birth,
        @NotBlank String phNum,
        @NotBlank String password
) {
}