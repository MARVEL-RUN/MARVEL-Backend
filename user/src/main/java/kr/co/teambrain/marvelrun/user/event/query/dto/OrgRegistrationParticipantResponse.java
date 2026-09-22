package kr.co.teambrain.marvelrun.user.event.query.dto;

import java.util.List;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;

/** 조회 당시 활성 단체원이며 수정 명단에서 사용할 현재 신청 식별자를 포함한다. */
public record OrgRegistrationParticipantResponse(
        String registrationId, String name, String email, String birth, String phNum, GenderClass gender,
        String eventCategoryId, String eventCategoryName,
        List<RegistrationSouvenirResponse> selectedSouvenirList,
        String address, String addressDetail, String guardianName, String guardianPhNum,
        RegistrationStatus registrationStatus
) { }
