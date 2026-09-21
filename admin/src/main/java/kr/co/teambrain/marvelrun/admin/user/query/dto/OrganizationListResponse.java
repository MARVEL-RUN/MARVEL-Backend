package kr.co.teambrain.marvelrun.admin.user.query.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class OrganizationListResponse {
    private long listNumber;           // 역순 넘버링
    private String organizationId;     // 단체 ID
    private String groupName;          // 단체명
    private String eventName;          // 참여 대회명
    private String leaderName;         // 단체대표자명
    private String loginId;            // 단체신청 로그인 ID
    private long memberCount;          // 회원수 (소프트 딜리트 제외)
    private LocalDateTime createdAt;   // 등록일(생성일)
}