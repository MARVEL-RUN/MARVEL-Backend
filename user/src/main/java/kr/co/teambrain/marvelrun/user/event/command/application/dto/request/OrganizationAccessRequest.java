package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 단체 수정 요청마다 Organization 소유권을
 * 다시 확인하기 위한 인증 입력값이다.
 *
 * 이전 조회에서 인증에 성공했더라도 그 결과를 보존하지 않으며,
 * 실제 수정 요청에서 loginId / password를 다시 전달받아
 * 현재 Organization 값과 재검증한다.
 */
public record OrganizationAccessRequest(
        @NotBlank String loginId,
        @NotBlank String password
) {
}