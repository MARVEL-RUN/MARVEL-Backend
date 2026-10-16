package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

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
            // 그룹 기능 임시 중단: 내부 호환 필드는 JSON·Swagger에서 제외한다.
            @JsonIgnore @Schema(hidden = true)
            String startGroupId,
            // 그룹 기능 임시 중단: 내부 호환 필드는 JSON·Swagger에서 제외한다.
            @JsonIgnore @Schema(hidden = true)
            Boolean startGroupUnselected
    ) {
        /** 미선택이 아니면 그룹 식별자를 반드시 받는다. */
        @JsonIgnore @Schema(hidden = true)
        // 그룹 기능 임시 중단: 재활성화 시 조합 검증 복원.
        // @AssertTrue(message = "그룹을 선택하거나 미선택을 체크해주세요.")
        public boolean isStartGroupSelectionValid() {
            return startGroupUnselected == null || startGroupUnselected
                    || (startGroupId != null && !startGroupId.isBlank());
        }
    }
}
