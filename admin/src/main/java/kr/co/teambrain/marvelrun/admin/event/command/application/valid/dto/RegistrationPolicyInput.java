package kr.co.teambrain.marvelrun.admin.event.command.application.valid.dto;


/**
 * 개인·단체 신청 및 향후 수정에서 사용하는 공통 정책 검증 입력.
 *
 * birth는 실제 참가자의 생년월일이다.
 *
 * 개인 신청:
 * - guardianName과 guardianConsent에 개인 요청값을 전달한다.
 *
 * 단체 신청:
 * - guardianName에 단체장 이름을 전달한다.
 * - guardianConsent에 단체장 동의 여부를 전달한다.
 */
public record RegistrationPolicyInput(
        String birth,
        String guardianName,
        Boolean guardianConsent
) {
}