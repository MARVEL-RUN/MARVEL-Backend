package kr.co.teambrain.marvelrun.admin.capacity.query.dto;

/** 정원을 점유한 참가자 한 명의 정보이며 개인 참가자의 단체명은 null이다. */
public record CapacityParticipantResponse(
        String registrationId, String name, String birth, String phNum,
        String organizationName
) { }
