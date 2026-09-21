package kr.co.teambrain.marvelrun.admin.user.query.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class OrganizationDetailResponse {
    private String organizationId;
    private String groupName;
    private String eventName;
    private String leaderName;
    private String loginId;
    private LocalDateTime createdAt;

    // 소속 인원 리스트
    private List<OrganizationMemberDto> members;
}