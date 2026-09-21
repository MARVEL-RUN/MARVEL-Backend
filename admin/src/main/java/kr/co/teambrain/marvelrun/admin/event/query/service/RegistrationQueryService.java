package kr.co.teambrain.marvelrun.admin.event.query.service;

import java.util.Map;
import java.util.stream.Collectors;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.command.domain.Payment;
import kr.co.teambrain.marvelrun.admin.event.command.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.repository.SouvenirQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.dto.LeaderInfoResponse;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationDetailResponse;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationListResponse;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationSearchCondition;
import kr.co.teambrain.marvelrun.admin.event.query.repository.PaymentQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.util.RegistrationSpecification;
import kr.co.teambrain.marvelrun.admin.user.command.domain.Organization;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegistrationQueryService {


    private final RegistrationQueryRepository registrationQueryRepository;
    private final PaymentQueryRepository paymentQueryRepository;

    private final SouvenirQueryRepository souvenirQueryRepository;

    public Page<RegistrationListResponse> getRegistrationList(
            RegistrationSearchCondition condition,
            Pageable pageable
    ) {
        Page<Registration> registrations = registrationQueryRepository.findAll(
                RegistrationSpecification.searchWith(condition),
                pageable
        );

        List<String> souvenirIds = registrations.getContent().stream()
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

        long totalElements = registrations.getTotalElements();
        int pageNumber = pageable.getPageNumber();
        int pageSize = pageable.getPageSize();

        return registrations.map(registration -> {
            long currentIndex = registrations.getContent().indexOf(registration);
            long listNumber = totalElements
                    - ((long) pageNumber * pageSize)
                    - currentIndex;

            return convertToDto(registration, listNumber, souvenirNames);
        });
    }

    private RegistrationListResponse convertToDto(Registration registration, long listNumber, Map<String, String> souvenirNames) {
        boolean isOrganization = registration.getOrganization() != null;
        String type = isOrganization ? "단체" : "개인";

        // 암호화 유틸리티를 제거하고 평문 그대로 매핑
        String plainName = registration.getName();
        String plainPhone = registration.getPhNum();

        String orgName = isOrganization ? registration.getOrganization().getGroupName() : null;

        String souvenirName = "";
        List<SouvenirJson> souvenirs = registration.getSouvenirJson();
        if (souvenirs != null && !souvenirs.isEmpty()) {
            souvenirName = souvenirNames.getOrDefault(
                    souvenirs.get(0).souvenirId(),
                    ""
            );
        }

        String marketingConsent = "N";
        if (Boolean.TRUE.equals(registration.getActiveUniqueInfo())) {
            marketingConsent = "Y";
        }

        String genderStr = registration.getGender() == GenderClass.M ? "남성" : "여성";

        return RegistrationListResponse.builder()
                .registrationId(registration.getId())
                .listNumber(listNumber)
                .type(type)
                .name(plainName)
                .orgName(orgName)
                .birth(registration.getBirth())
                .gender(genderStr)
                .courseName(registration.getEventCategory().getName())
                .souvenirName(souvenirName)
                .phoneNumber(plainPhone)
                .marketingConsent(marketingConsent)
                .status(registration.getStatus().name())
                .createdAt(registration.getRegistrationDate())
                .build();
    }

    /**
     * 신청 상세 정보 조회
     */
    public RegistrationDetailResponse getRegistrationDetail(String registrationId) {
        Registration registration = registrationQueryRepository.findById(registrationId)
                .orElseThrow(() -> new CustomException(ErrorCode.REGISTRATION_NOT_FOUND));

        // 해당 신청건의 가장 최근 결제 정보 조회 (존재하지 않을 수 있음)[cite: 10]
        Payment payment = paymentQueryRepository.findFirstByRegistrationIdOrderByCreatedAtDesc(registrationId)
                .orElse(null);

        return convertToDetailDto(registration, payment);
    }

    private RegistrationDetailResponse convertToDetailDto(Registration registration, Payment payment) {
        boolean isOrganization = registration.getOrganization() != null;

        // 이메일 추출: 단체면 단체 대표 이메일, 개인이면 유저 이메일
        String email = null;
        String organizationId = null;

        LeaderInfoResponse leaderInfoResponse = null;
        if (isOrganization) {
            Organization targetOrganization = registration.getOrganization();

            email = targetOrganization.getEmail();
            organizationId = targetOrganization.getId();

            leaderInfoResponse = new LeaderInfoResponse(
                    targetOrganization.getGroupName(),
                    targetOrganization.getLeaderName(),
                    targetOrganization.getLeaderBirth(),
                    targetOrganization.getLeaderPhNum(),
                    targetOrganization.getAddress(),
                    targetOrganization.getAddressDetail()
            );
        } else if (registration.getUser() != null) {
            email = registration.getUser().getEmail();
        }

        // 단체명 처리
        String orgName = isOrganization ? registration.getOrganization().getGroupName() : "-";

        // 기념품 정보 파싱
        String souvenirName = "-";
        String souvenirSize = "-";
        List<SouvenirJson> souvenirs = registration.getSouvenirJson();
        if (souvenirs != null && !souvenirs.isEmpty()) {
            SouvenirJson selected = souvenirs.get(0);

            souvenirName = souvenirQueryRepository
                    .findNamesByIds(List.of(selected.souvenirId()))
                    .stream()
                    .map(SouvenirQueryRepository.SouvenirNameProjection::getName)
                    .findFirst()
                    .orElse("-");

            souvenirSize = selected.selectedSize() != null
                    ? selected.selectedSize()
                    : "-";
        }

        // 보호자 정보 널 체크
        String guardianPhone = registration.getGuardianPhNum() != null ? registration.getGuardianPhNum() : "-";
        String guardianRel = registration.getGuardianRelationship() != null ? registration.getGuardianRelationship() : "-";

        // 결제 정보 널 체크[cite: 10]
        String orderId = payment != null ? payment.getOrderId() : "-";
        String paymentStatus = payment != null
                ? ("UNKNOWN".equals(payment.getProcessStatus().name()) ? registration.getStatus().name() : payment.getProcessStatus().name())
                : "-";

        String paymentMethod = payment != null && payment.getPaymentMethod() != null
                ? payment.getPaymentMethod().name() : "-";

        return RegistrationDetailResponse.builder()
                .name(registration.getName())
                .orgName(orgName)
                .courseName(registration.getEventCategory().getName())
                .souvenirName(souvenirName)
                .souvenirSize(souvenirSize)
                .gender(registration.getGender() == GenderClass.M ? "남성" : "여성")
                .birth(registration.getBirth())
                .phoneNumber(registration.getPhNum())
                .email(email != null ? email : "-")
                .guardianPhoneNumber(guardianPhone)
                .guardianRelationship(guardianRel)
                .createdAt(registration.getRegistrationDate())
                .amount(registration.getContractAmount())
                .orderId(orderId)
                .paymentMethod(paymentMethod)
                .paymentStatus(paymentStatus)
                .address(registration.getAddress() != null ? registration.getAddress() : "-")
                .addressDetail(registration.getAddressDetail() != null ? registration.getAddressDetail() : "-")
                .organizationId(organizationId)
                .leaderInfo(
                        leaderInfoResponse
                )
                .build();
    }
}