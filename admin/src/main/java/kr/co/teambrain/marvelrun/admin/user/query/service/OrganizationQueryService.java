package kr.co.teambrain.marvelrun.admin.user.query.service;

import kr.co.teambrain.marvelrun.admin.discount.query.ReferenceFilterQueryService;
import kr.co.teambrain.marvelrun.admin.discount.query.ReferenceFilterSummaryResponse;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationActionType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategoryStartGroup;
import kr.co.teambrain.marvelrun.admin.event.command.repository.EventCategoryStartGroupRepository;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyModels;
import kr.co.teambrain.marvelrun.admin.event.query.repository.PaymentQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.SouvenirQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationActionPolicyReader;
import kr.co.teambrain.marvelrun.admin.user.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.admin.user.query.dto.OrganizationDetailResponse;
import kr.co.teambrain.marvelrun.admin.user.query.dto.OrganizationListResponse;
import kr.co.teambrain.marvelrun.admin.user.query.dto.OrganizationMemberDto;
import kr.co.teambrain.marvelrun.admin.user.query.dto.OrganizationSearchCondition;
import kr.co.teambrain.marvelrun.admin.user.query.repository.OrganizationQueryRepository;
import kr.co.teambrain.marvelrun.admin.user.query.util.OrganizationSpecification;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 단체 목록·상세와 참가자별 정책 및 저장된 그룹을 조회한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrganizationQueryService {
    private final ReferenceFilterQueryService referenceFilters;
    private final RegistrationActionPolicyReader actionPolicies;
    private final ServerTimeProvider policyTime;


    private final OrganizationQueryRepository organizationQueryRepository;
    private final RegistrationQueryRepository registrationQueryRepository;
    private final SouvenirQueryRepository souvenirQueryRepository;
    private final PaymentQueryRepository paymentQueryRepository;
    private final EventCategoryStartGroupRepository startGroupRepository;

    public Page<OrganizationListResponse> getOrganizationList(
            OrganizationSearchCondition condition,
            Pageable pageable
    ) {
        Page<Organization> organizations = organizationQueryRepository.findAll(
                OrganizationSpecification.searchWith(condition),
                pageable
        );

        Map<String, ReferenceFilterSummaryResponse> filterSummaries = referenceFilters.loadReferenceFilterSummaries(
                organizations.getContent().stream().map(Organization::getReferenceFilterId).toList());
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

            return convertToDto(organization, listNumber, memberCount, filterSummaries);
        });
    }

    private OrganizationListResponse convertToDto(Organization organization, long listNumber, long memberCount, Map<String, ReferenceFilterSummaryResponse> filterSummaries) {
        return OrganizationListResponse.builder()
                .referenceFilter(filterSummaries.get(organization.getReferenceFilterId()))
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

    /** 단체 상세와 참가자별 현재 그룹 코드를 일괄 조회하여 응답한다. */
    public OrganizationDetailResponse getOrganizationDetail(String organizationId) {
        // 1. 단체 정보 조회
        Organization organization = organizationQueryRepository.findById(organizationId)
                .orElseThrow(() -> new CustomException(ErrorCode.ORGANIZATION_NOT_FOUND));

        LocalDateTime now = policyTime.currentDateTime();
        List<RegistrationActionPolicyModels.RegistrationActionPolicyInput> policies = actionPolicies.loadEnabledRegistrationActionPolicies(organization.getEvent().getId(), List.of(RegistrationActionType.MODIFY, RegistrationActionType.REFUND, RegistrationActionType.PAYMENT, RegistrationActionType.REGIST));
        String email = organization.getEmail();

        // 2. 소속된 신청자 목록 조회 (삭제된 인원 제외)
        List<Registration> registrations = registrationQueryRepository
                .findByOrganizationIdAndSoftDeletedFalse(organizationId);

        // 기존 비활성 그룹도 표시하며 중복 ID를 제거하여 한 번에 조회한다.
        List<String> groupIds = registrations.stream().map(Registration::getStartGroupId)
                .filter(Objects::nonNull).distinct().toList();
        Map<String, String> groupCodes = groupIds.isEmpty() ? Map.of()
                : startGroupRepository.findByIdIn(groupIds).stream()
                .collect(Collectors.toMap(EventCategoryStartGroup::getId, EventCategoryStartGroup::getCode));

        // 3. 신청자들의 기념품 ID 추출 및 매핑
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

        // 4. 단체의 최신 결제 내역 조회 (UNKNOWN 상태 확인 목적)
        Payment latestPayment = paymentQueryRepository.findFirstByOrganization_IdOrderByCreatedAtDesc(organizationId)
                .orElse(null);
        boolean isPaymentUnknown = latestPayment != null
                && latestPayment.getProcessStatus() == PaymentProcessStatus.UNKNOWN;

        List<String> filterIds = new ArrayList<>(registrations.stream().map(Registration::getReferenceFilterId).toList());
        filterIds.add(organization.getReferenceFilterId());
        Map<String, ReferenceFilterSummaryResponse> filterSummaries = referenceFilters.loadReferenceFilterSummaries(filterIds);

        // 5. 소속 인원 DTO 변환
        List<OrganizationMemberDto> members = new ArrayList<>();
        long listNumber = 1; // 화면 UI에 맞춰 1번부터 오름차순 부여

        for (Registration reg : registrations) {
            String sName = "-";
            String sSize = "-";
            List<SouvenirJson> souvenirs = reg.getSouvenirJson();

            if (souvenirs != null && !souvenirs.isEmpty()) {
                SouvenirJson selected = souvenirs.get(0);
                sName = souvenirNames.getOrDefault(selected.souvenirId(), "-");
                sSize = selected.selectedSize() != null ? selected.selectedSize() : "-";
            }

            // 표시용 성별과 실제 약관 동의값을 변환한다.
            String genderStr = reg.getGender() == GenderClass.M ? "남성" : "여성";
            String marketingConsent = Boolean.TRUE.equals(reg.getTermsMarketingAgreed()) ? "Y" : "N";
            String finalStatus = isPaymentUnknown ? "UNKNOWN" : reg.getStatus().name();

            members.add(OrganizationMemberDto.builder()
                    .referenceFilter(filterSummaries.get(reg.getReferenceFilterId()))
                    .memberPolicy(actionPolicies.evaluateOrganizationMemberPolicy(reg, now, policies))
                    .listNumber(listNumber++) // 페이징 번호 할당
                    .registrationId(reg.getId())
                    .startGroupCode(reg.getStartGroupId() == null ? null : groupCodes.get(reg.getStartGroupId()))
                    .startGroupUnselected(reg.isStartGroupUnselected())
                    .name(reg.getName())
                    .birth(reg.getBirth())
                    .gender(genderStr)
                    .courseName(reg.getEventCategory().getName())
                    .souvenirName(sName)
                    .souvenirSize(sSize)
                    .phoneNumber(reg.getPhNum())
                    .marketingConsent(marketingConsent)
                    .status(finalStatus)
                    .createdAt(reg.getRegistrationDate()) // RegistrationBase의 신청일시 필드
                    .amount(reg.getContractAmount())
                    .build());
        }

        // 6. 최종 상세 응답 DTO 반환
        return OrganizationDetailResponse.builder()
                .referenceFilter(filterSummaries.get(organization.getReferenceFilterId()))
                .organizationPolicy(actionPolicies.evaluateOrganizationUserPolicy(organization.getEvent(), registrations, now, policies))
                .organizationId(organization.getId())
                .groupName(organization.getGroupName())
                .eventName(organization.getEvent().getNameKr())
                .leaderName(organization.getLeaderName())
                .leaderBirth(organization.getLeaderBirth())
                .leaderPhNum(organization.getLeaderPhNum())
                .address(organization.getAddress())
                .addressDetail(organization.getAddressDetail())
                .guardianConsent(organization.isGuardianConsent())
                .loginId(organization.getLoginId())
                .createdAt(organization.getCreatedAt())
                .members(members)
                .email(email)
                .build();
    }
}
