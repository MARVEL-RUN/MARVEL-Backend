package kr.co.teambrain.marvelrun.admin.auth.command.application.dto;

import kr.co.teambrain.marvelrun.admin.auth.command.application.dto.reseponse.AdminLoginResponse;

/** service -> controller 전달용, controller에서 본 값을 쿠키로 포함시켜 수정해 내림 */
public record AdminLoginSuccess(

        String accessToken,

        String refreshToken,

        AdminLoginResponse response

) {
}