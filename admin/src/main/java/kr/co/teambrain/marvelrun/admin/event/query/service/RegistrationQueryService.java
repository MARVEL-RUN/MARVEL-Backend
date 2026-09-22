package kr.co.teambrain.marvelrun.admin.event.query.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;

import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationStatDto;
import kr.co.teambrain.marvelrun.admin.event.query.dto.response.EventStatisticsResponse;
import kr.co.teambrain.marvelrun.admin.event.query.repository.SouvenirQueryRepository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.query.dto.response.LeaderInfoResponse;
import kr.co.teambrain.marvelrun.admin.event.query.dto.response.RegistrationDetailResponse;
import kr.co.teambrain.marvelrun.admin.event.query.dto.response.RegistrationListResponse;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationSearchCondition;
import kr.co.teambrain.marvelrun.admin.event.query.repository.PaymentQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.util.RegistrationSpecification;
import kr.co.teambrain.marvelrun.admin.user.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentMethod;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegistrationQueryService {


    private final RegistrationQueryRepository registrationQueryRepository;
    private final PaymentQueryRepository paymentQueryRepository;

    private final SouvenirQueryRepository souvenirQueryRepository;
    private static final LocalDate CHILD_CUTOFF_DATE = LocalDate.of(2013, 11, 1);

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

        Payment payment = paymentQueryRepository.findFirstByRegistrationIdOrderByCreatedAtDesc(registration.getId())
                .orElse(null);

        String marketingConsent = "N";
        if (Boolean.TRUE.equals(registration.getTermsMarketingAgreed())) {
            marketingConsent = "Y";
        }

        String genderStr = registration.getGender() == GenderClass.M ? "남성" : "여성";
        String statusStr = (payment != null && payment.getProcessStatus() == PaymentProcessStatus.UNKNOWN)
                ? PaymentProcessStatus.UNKNOWN.name()
                : registration.getStatus().name();

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
                .status(statusStr)
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
        // ✅ Registration의 이메일을 최우선으로 확인
        String email = registration.getEmail();
        String organizationId = null;

        LeaderInfoResponse leaderInfoResponse = null;
        if (isOrganization) {
            Organization targetOrganization = registration.getOrganization();

            email = targetOrganization.getEmail();
            organizationId = targetOrganization.getId();

            leaderInfoResponse = new LeaderInfoResponse(
                    targetOrganization.getGroupName(),
                    targetOrganization.getLeaderName(),
                    targetOrganization.getLeaderPhNum(),
                    targetOrganization.getLeaderBirth(),
                    targetOrganization.getAddress(),
                    targetOrganization.getAddressDetail()
            );
        } else if (registration.getUser() != null) {
            if (email == null || email.isBlank()) {
                email = registration.getUser().getEmail();
            }
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
        String guardianName = registration.getGuardianName() != null ? registration.getGuardianName() : "-";


        // 결제 정보 널 체크[cite: 10]
        String orderId = payment != null ? payment.getOrderId() : "-";

        String paymentStatus = (payment != null && payment.getProcessStatus() == PaymentProcessStatus.UNKNOWN)
                ? PaymentProcessStatus.UNKNOWN.name()
                : registration.getStatus().name();

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
                .email(email != null ? email : "-") // 변환된 이메일 삽입
                .guardianConsent(registration.isGuardianConsent())
                .guardianName(guardianName)
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
                .termsEssentialAgreed(registration.getTermsEssentialAgreed())
                .termsMarketingAgreed(registration.getTermsMarketingAgreed())
                .termsMarketingChannelAgreed(registration.getTermsMarketingChannelAgreed())
                .build();
    }

    public EventStatisticsResponse getEventStatistics(String eventId) {
        List<RegistrationStatDto> statsData = registrationQueryRepository.findStatsByEventId(eventId);

        // 1. 코스 헤더 추출
        List<String> courseNames = statsData.stream()
                .map(RegistrationStatDto::courseName)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        // 2. 행(Row) 라벨 정의
        List<String> genderLabels = List.of("남", "여", "합계");
        List<String> ageLabels = List.of("10대 이하", "20대", "30대", "40대", "50대", "60대 이상", "합계");
        List<String> childLabels = List.of("일반", "아동", "합계");

        // 3. 통계 빌더 초기화
        Map<String, StatRowBuilder> genderBuilders = initBuilders(genderLabels, courseNames);
        Map<String, StatRowBuilder> ageBuilders = initBuilders(ageLabels, courseNames);
        Map<String, StatRowBuilder> childBuilders = initBuilders(childLabels, courseNames);

        int currentYear = LocalDate.now().getYear();

        // 4. 단일 루프 집계
        for (RegistrationStatDto data : statsData) {
            // 결제 수단 명확화 (paymentMethod가 존재해야 입금자, 없으면 미결제)
            boolean isCard = data.paymentMethod() == PaymentMethod.CARD;
            boolean isEasyPay = data.paymentMethod() == PaymentMethod.EASY_PAY;
            boolean isPaid = isCard || isEasyPay; // 입금자(결제자) 여부
            boolean isUnpaid = !isPaid;           // null 포함 미결제 여부

            boolean isPersonal = data.organizationId() == null;
            boolean isGroup = !isPersonal;
            String course = data.courseName();

            // [성별 집계]
            String genderLabel = data.gender() == GenderClass.M ? "남" : "여";
            addStats(genderBuilders, "합계", course, isPaid, isCard, isEasyPay, isUnpaid, isPersonal, isGroup);
            if (data.gender() != null) {
                addStats(genderBuilders, genderLabel, course, isPaid, isCard, isEasyPay, isUnpaid, isPersonal, isGroup);
            }

            // [연령대 집계]
            String ageLabel = getAgeGroup(data.birth(), currentYear);
            addStats(ageBuilders, "합계", course, isPaid, isCard, isEasyPay, isUnpaid, isPersonal, isGroup);
            if (ageLabel != null) {
                addStats(ageBuilders, ageLabel, course, isPaid, isCard, isEasyPay, isUnpaid, isPersonal, isGroup);
            }

            // [아동 여부 집계 (2013-11-01 기준)]
            String childLabel = getChildGroup(data.birth());
            addStats(childBuilders, "합계", course, isPaid, isCard, isEasyPay, isUnpaid, isPersonal, isGroup);
            if (childLabel != null) {
                addStats(childBuilders, childLabel, course, isPaid, isCard, isEasyPay, isUnpaid, isPersonal, isGroup);
            }
        }

        // 5. 응답 조립
        return EventStatisticsResponse.builder()
                .courseHeaders(courseNames)
                .genderStats(buildRows(genderLabels, genderBuilders))
                .ageGroupStats(buildRows(ageLabels, ageBuilders))
                .childStats(buildRows(childLabels, childBuilders))
                .build();
    }

    private Map<String, StatRowBuilder> initBuilders(List<String> labels, List<String> courses) {
        Map<String, StatRowBuilder> map = new LinkedHashMap<>();
        for (String label : labels) map.put("신청자(" + label + ")", new StatRowBuilder("신청자(" + label + ")", courses));
        for (String label : labels) map.put("입금자(" + label + ")", new StatRowBuilder("입금자(" + label + ")", courses));
        return map;
    }

    private void addStats(Map<String, StatRowBuilder> builders, String label, String course,
                          boolean isPaid, boolean isCard, boolean isEasyPay, boolean isUnpaid,
                          boolean isPersonal, boolean isGroup) {
        // 신청자는 무조건 카운트
        builders.get("신청자(" + label + ")").add(course, isCard, isEasyPay, isUnpaid, isPersonal, isGroup);

        // 입금자는 결제수단이 존재하는(isPaid) 경우에만 카운트하며, 미결제(unpaid) 파라미터는 무조건 false로 고정
        if (isPaid) {
            builders.get("입금자(" + label + ")").add(course, isCard, isEasyPay, false, isPersonal, isGroup);
        }
    }

    private List<EventStatisticsResponse.StatRowDto> buildRows(List<String> labels, Map<String, StatRowBuilder> builders) {
        List<EventStatisticsResponse.StatRowDto> rows = new ArrayList<>();
        for (String label : labels) rows.add(builders.get("신청자(" + label + ")").build());
        for (String label : labels) rows.add(builders.get("입금자(" + label + ")").build());
        return rows;
    }

    private String getAgeGroup(String birthStr, int currentYear) {
        if (birthStr == null || birthStr.isBlank()) return null;
        try {
            String cleanBirth = birthStr.replaceAll("[^0-9]", ""); // 숫자만 추출
            if (cleanBirth.length() < 4) return null;

            int birthYear = Integer.parseInt(cleanBirth.substring(0, 4));
            int age = currentYear - birthYear;

            if (age < 20) return "10대 이하";
            if (age < 30) return "20대";
            if (age < 40) return "30대";
            if (age < 50) return "40대";
            if (age < 60) return "50대";
            return "60대 이상";
        } catch (Exception e) {
            return null;
        }
    }

    private String getChildGroup(String birthStr) {
        if (birthStr == null || birthStr.isBlank()) return "일반"; // 누락 데이터 기본값
        try {
            String cleanBirth = birthStr.replaceAll("[^0-9]", ""); // 하이픈 제거
            if (cleanBirth.length() != 8) return "일반";

            // 2013년 11월 1일 이후 출생자 비교
            int birthDateNum = Integer.parseInt(cleanBirth);
            return birthDateNum >= 20131101 ? "아동" : "일반";
        } catch (Exception e) {
            return "일반";
        }
    }

    private static class StatRowBuilder {
        String classification;
        Map<String, Long> courseCounts = new LinkedHashMap<>();
        long totalCount, cardCount, easyPayCount, unpaidCount, personalCount, groupCount;

        StatRowBuilder(String classification, List<String> courseNames) {
            this.classification = classification;
            courseNames.forEach(name -> courseCounts.put(name, 0L));
        }

        void add(String course, boolean card, boolean easyPay, boolean unpaid, boolean personal, boolean group) {
            if (course != null) {
                courseCounts.put(course, courseCounts.getOrDefault(course, 0L) + 1);
            }
            totalCount++;
            if (card) cardCount++;
            if (easyPay) easyPayCount++;
            if (unpaid) unpaidCount++;
            if (personal) personalCount++;
            if (group) groupCount++;
        }

        EventStatisticsResponse.StatRowDto build() {
            return new EventStatisticsResponse.StatRowDto(classification, courseCounts, totalCount, cardCount, easyPayCount, unpaidCount, personalCount, groupCount);
        }
    }
}