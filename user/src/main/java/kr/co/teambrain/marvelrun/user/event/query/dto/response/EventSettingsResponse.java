package kr.co.teambrain.marvelrun.user.event.query.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.co.teambrain.marvelrun.common.entity.EventBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventFormat;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ParticipantAuthMethod;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ParticipantCredentialType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ParticipantIdentifierType;

/** 화면이 대회마다 메뉴와 입력 칸을 정하는 데 쓰는 대회 설정이다. 내부 순번(last_bib_seq)은 내보내지 않는다. */
@Schema(description = "대회 설정. 화면이 단체 신청 메뉴, 참가자 인증 입력 칸, 배번 안내를 정할 때 사용합니다.")
public record EventSettingsResponse(
        @Schema(description = "대회 형식. `OFFLINE`: 현장 대회, `VIRTUAL`: 버추얼런")
        EventFormat eventFormat,

        ParticipantAuth participantAuth,

        @Schema(description = "결제 승인 때 배번을 자동으로 발급하는지 여부")
        boolean bibAutoIssue,

        @Schema(description = "단체 신청을 받는지 여부. false면 단체 신청 생성과 구성원 추가가 거절됩니다.")
        boolean groupRegistrationEnabled
) {

    @Schema(description = "참가자가 자기 신청에 들어오는 방식. 화면은 method 이름이 아니라 identifierType·credentialType으로 입력 칸을 정합니다. 처음 보는 method 값이 와도 두 종류로 동작해야 합니다.")
    public record ParticipantAuth(
            @Schema(description = "인증 방식. `PERSONAL_INFO_PASSWORD`: 이름·생년월일·휴대폰 + 비밀번호, `BIB_PHONE_OTP`: 배번 + 휴대폰 인증번호")
            ParticipantAuthMethod method,

            @Schema(description = "신청을 찾는 수단. `PERSONAL_INFO`: 이름·생년월일·휴대폰, `BIB`: 배번")
            ParticipantIdentifierType identifierType,

            @Schema(description = "본인을 확인하는 수단. `PASSWORD`: 비밀번호, `PHONE_OTP`: 휴대폰 인증번호")
            ParticipantCredentialType credentialType
    ) {
    }

    public static EventSettingsResponse from(EventBase event) {
        ParticipantAuthMethod method = event.getParticipantAuthMethod();
        return new EventSettingsResponse(
                event.getEventFormat(),
                new ParticipantAuth(method, method.getIdentifierType(), method.getCredentialType()),
                Boolean.TRUE.equals(event.getBibAutoIssue()),
                // 값이 없으면 컬럼 기본값(허용)으로 본다. 차단 검사와 같은 기준이다.
                !Boolean.FALSE.equals(event.getGroupRegistrationEnabled())
        );
    }
}
