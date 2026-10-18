package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationParticipantRequest;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 신규 단체의 인원수와 선택 코드의 요청 경계를 검증한다. */
class OrgRegistrationCreateRequestTest {
    /** 한 명은 registrations 자체 제약으로 차단하고 두 명이면 해당 제약을 통과한다. */
    @Test
    void requiresTwoParticipantsWithoutRequiringLinkCode() {
        OrgRegistrationParticipantRequest member = new OrgRegistrationParticipantRequest("category",
                List.of(new SouvenirJson("souvenir","S")),"참가자","010-0000-0000","1990-01-01",GenderClass.M,null,true);
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            OrgRegistrationCreateRequest one = new OrgRegistrationCreateRequest(null,null,List.of(member),true,false,false,null);
            OrgRegistrationCreateRequest two = new OrgRegistrationCreateRequest(null,null,List.of(member,member),true,false,false,null);
            assertThat(factory.getValidator().validate(one)).anyMatch(error -> error.getPropertyPath().toString().equals("registrations"));
            assertThat(factory.getValidator().validate(two)).noneMatch(error -> error.getPropertyPath().toString().equals("registrations")
                    || error.getPropertyPath().toString().equals("discountLinkCode"));
        }
    }
}
