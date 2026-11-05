package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;

/** 패키지 하위 종목별 받는 사람. 배송지는 신청자 주소 하나를 쓴다. */
public record RegistrationEntryRequest(

        /** 패키지의 하위 종목 ID */
        @NotBlank
        @Size(max = 40)
        String eventCategoryId,

        @NotBlank
        @Size(max = 50)
        String name,

        @NotBlank
        @Size(max = 14)
        String phNum,

        @NotBlank
        @Size(max = 10)
        String birth,

        @NotNull
        GenderClass gender
) {
}
