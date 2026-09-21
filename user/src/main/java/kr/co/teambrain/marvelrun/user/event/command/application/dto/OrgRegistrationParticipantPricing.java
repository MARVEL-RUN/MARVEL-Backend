package kr.co.teambrain.marvelrun.user.event.command.application.dto;

import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationModificationCandidateContext;

/**
 * 단체 수정의 참가자 후보 한 명과 해당 가격 계산 결과를 연결한다.
 *
 * 신규 참가자는 registrationId가 없으므로 ID만으로 결과를 매핑하지 않는다.
 * 검증된 후보 자체를 함께 전달하여 요청과 계산 결과의 대응을 유지한다.
 */
public record OrgRegistrationParticipantPricing(
        OrgRegistrationModificationCandidateContext.ParticipantCandidate candidate,
        RegistrationModificationPrice price
) {
}