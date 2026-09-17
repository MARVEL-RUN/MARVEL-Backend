package kr.co.teambrain.marvelrun.admin.auth.command.application.dto;

public record AdminLoginRequest(

        String loginId,

        String password

) {
}