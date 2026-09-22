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
    private String leaderBirth;        // 추가: 대표자 생년월일
    private String leaderPhNum;        // 추가: 대표자 연락처
    private String loginId;
    private LocalDateTime createdAt;
    private String email;
    private String address;            // 추가: 주소
    private String addressDetail;      // 추가: 상세주소
    private boolean guardianConsent;   // 추가: 법정대리인 동의 여부

    // 소속 인원 리스트
    private List<OrganizationMemberDto> members;
}