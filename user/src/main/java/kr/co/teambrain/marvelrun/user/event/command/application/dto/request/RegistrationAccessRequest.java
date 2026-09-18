package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;


import jakarta.validation.constraints.NotBlank;

/**
 * 개인 신청의 본인 확인에 사용하는 입력이다.
 *
 * 신청 시 저장한 이름, 생년월일, 전화번호, 비밀번호와 대조한다.
 * 확보 반환과 재결제 요청에서 공통으로 사용한다.
 */
public record RegistrationAccessRequest(
        @NotBlank String name,
        @NotBlank String birth,
        @NotBlank String phNum,
        @NotBlank String password
) {
}