package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 단체 비밀번호로 인증하고 전달한 참가자들의 그룹만 일괄 수정한다. */
public record OrgRegistrationStartGroupModificationRequest(
        @NotBlank String password,
        @NotEmpty List<@NotNull @Valid Participant> registrations
) {
    /** 명단 추가·제거 없이 기존 참가자 한 명의 그룹 선택을 표현한다. */
    public record Participant(
            @NotBlank String registrationId,
            String startGroupId,
            @NotNull Boolean startGroupUnselected
    ) {
        /** 미선택이 아니면 그룹 식별자를 반드시 받는다. */
        @JsonIgnore
        @AssertTrue(message = "그룹을 선택하거나 미선택을 체크해주세요.")
        public boolean isStartGroupSelectionValid() {
            return startGroupUnselected == null || startGroupUnselected
                    || (startGroupId != null && !startGroupId.isBlank());
        }
    }
}
