package kr.co.teambrain.marvelrun.admin.user.query.service;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.command.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.repository.SouvenirQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationQueryRepository;
import kr.co.teambrain.marvelrun.admin.user.command.domain.Organization;
import kr.co.teambrain.marvelrun.admin.user.query.dto.OrganizationDetailResponse;
import kr.co.teambrain.marvelrun.admin.user.query.dto.OrganizationListResponse;
import kr.co.teambrain.marvelrun.admin.user.query.dto.OrganizationMemberDto;
import kr.co.teambrain.marvelrun.admin.user.query.dto.OrganizationSearchCondition;
import kr.co.teambrain.marvelrun.admin.user.query.repository.OrganizationQueryRepository;
import kr.co.teambrain.marvelrun.admin.user.query.util.OrganizationSpecification;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrganizationQueryService {

    private final OrganizationQueryRepository organizationQueryRepository;
    private final RegistrationQueryRepository registrationQueryRepository;
    private final SouvenirQueryRepository souvenirQueryRepository;

    public Page<OrganizationListResponse> getOrganizationList(
            OrganizationSearchCondition condition,
            Pageable pageable
    ) {
        Page<Organization> organizations = organizationQueryRepository.findAll(
                OrganizationSpecification.searchWith(condition),
                pageable
        );

        long totalElements = organizations.getTotalElements();
        int pageNumber = pageable.getPageNumber();
        int pageSize = pageable.getPageSize();

        return organizations.map(organization -> {
            long currentIndex = organizations.getContent().indexOf(organization);
            long listNumber = totalElements
                    - ((long) pageNumber * pageSize)
                    - currentIndex;

            // 해당 단체의 유효한(softDeleted = false) 회원 수 조회
            long memberCount = registrationQueryRepository
                    .countByOrganizationIdAndSoftDeletedFalse(organization.getId());

            return convertToDto(organization, listNumber, memberCount);
        });
    }

    private OrganizationListResponse convertToDto(Organization organization, long listNumber, long memberCount) {
        return OrganizationListResponse.builder()
                .listNumber(listNumber)
                .organizationId(organization.getId())
                .groupName(organization.getGroupName())
                .eventName(organization.getEvent().getNameKr())
                .leaderName(organization.getLeaderName())
                .loginId(organization.getLoginId())
                .memberCount(memberCount)
                .createdAt(organization.getCreatedAt())
                .build();
    }

    public OrganizationDetailResponse getOrganizationDetail(String organizationId) {
        // 1. 단체 정보 조회
        Organization organization = organizationQueryRepository.findById(organizationId)
                .orElseThrow(() -> new CustomException(ErrorCode.ORGANIZATION_NOT_FOUND));

        // 2. 소속된 신청자 목록 조회 (삭제된 인원 제외)
        List<Registration> registrations = registrationQueryRepository
                .findByOrganizationIdAndSoftDeletedFalse(organizationId);

        // 3. 신청자들의 기념품 ID를 추출하여 한 번에 기념품명 맵핑 (N+1 방지)
        List<String> souvenirIds = registrations.stream()
                .map(Registration::getSouvenirJson)
                .filter(souvenirs -> souvenirs != null && !souvenirs.isEmpty())
                .map(souvenirs -> souvenirs.get(0).souvenirId())
                .distinct()
                .toList();

        Map<String, String> souvenirNames = souvenirIds.isEmpty()
                ? Map.of()
                : souvenirQueryRepository.findNamesByIds(souvenirIds).stream()
                .collect(Collectors.toMap(
                        SouvenirQueryRepository.SouvenirNameProjection::getId,
                        SouvenirQueryRepository.SouvenirNameProjection::getName
                ));

        // 4. 소속 인원 DTO 변환
        List<OrganizationMemberDto> members = registrations.stream().map(reg -> {
            String sName = "-";
            String sSize = "-";
            List<SouvenirJson> souvenirs = reg.getSouvenirJson();

            if (souvenirs != null && !souvenirs.isEmpty()) {
                SouvenirJson selected = souvenirs.get(0);
                sName = souvenirNames.getOrDefault(selected.souvenirId(), "-");
                sSize = selected.selectedSize() != null ? selected.selectedSize() : "-";
            }

            return OrganizationMemberDto.builder()
                    .registrationId(reg.getId())
                    .name(reg.getName())
                    .courseName(reg.getEventCategory().getName())
                    .souvenirName(sName)
                    .souvenirSize(sSize)
                    .birth(reg.getBirth())
                    .amount(reg.getContractAmount())
                    .build();
        }).toList();

        // 5. 최종 상세 응답 DTO 반환
        return OrganizationDetailResponse.builder()
                .organizationId(organization.getId())
                .groupName(organization.getGroupName())
                .eventName(organization.getEvent().getNameKr())
                .leaderName(organization.getLeaderName())
                .loginId(organization.getLoginId())
                .createdAt(organization.getCreatedAt())
                .members(members)
                .build();
    }
}