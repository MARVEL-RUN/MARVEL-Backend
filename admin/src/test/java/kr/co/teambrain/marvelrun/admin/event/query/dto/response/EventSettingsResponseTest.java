package kr.co.teambrain.marvelrun.admin.event.query.dto.response;

import kr.co.teambrain.marvelrun.common.inheritance_enum.ParticipantAuthMethod;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ParticipantCredentialType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ParticipantIdentifierType;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 설정값이 비어 있는 대회도 응답을 만들 수 있는지 확인한다. */
class EventSettingsResponseTest {

    /** 인증 방식이 비어 있으면 컬럼 기본값(기존 비밀번호 방식)으로 응답하고, 단체 신청은 허용으로 본다. */
    @Test
    void treatsUnsetValuesAsColumnDefaults() {
        // mock은 Boolean에 false를 돌려주므로 비어 있는 값을 직접 지정한다.
        Event event = mock(Event.class);
        when(event.getGroupRegistrationEnabled()).thenReturn(null);
        when(event.getBibAutoIssue()).thenReturn(null);

        EventSettingsResponse settings = EventSettingsResponse.from(event);

        assertThat(settings.participantAuth().method()).isEqualTo(ParticipantAuthMethod.PERSONAL_INFO_PASSWORD);
        assertThat(settings.participantAuth().identifierType()).isEqualTo(ParticipantIdentifierType.PERSONAL_INFO);
        assertThat(settings.participantAuth().credentialType()).isEqualTo(ParticipantCredentialType.PASSWORD);
        assertThat(settings.groupRegistrationEnabled()).isTrue();
        assertThat(settings.bibAutoIssue()).isFalse();
    }
}
