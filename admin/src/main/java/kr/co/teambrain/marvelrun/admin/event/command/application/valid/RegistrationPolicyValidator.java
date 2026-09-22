package kr.co.teambrain.marvelrun.admin.event.command.application.valid;

import kr.co.teambrain.marvelrun.common.inheritance_enum.EventStatus;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategorySouvenir;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Souvenir;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.policy.EventCategoryRegistrationPolicy;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.policy.EventCategorySouvenirPolicy;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.policy.EventRegistrationPolicy;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.dto.RegistrationPolicyInput;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 참가신청의 기간·출생일·보호자·사이즈 정책을 검증한다.
 *
 * Entity 및 정책 조회는 호출부에서 수행한다.
 * 이 클래스는 조회된 정보를 이용한 정책 판단만 담당한다.
 *
 * Capacity 잔여수량 및 Pricing 금액 계산은 처리하지 않는다.
 */
@Component
public class RegistrationPolicyValidator {

    private static final Pattern BIRTH_PATTERN =
            Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    private static final DateTimeFormatter BIRTH_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd")
                    .withResolverStyle(ResolverStyle.STRICT);

    

    /**
     * 신규신청의 대회 상태와 기간을 검증한다.
     *
     * 시작 정각은 허용하고, 마감 정각부터 차단한다.
     * autoStart / autoDeadline / visibleStatus는 다루지 않는다.
     */
    public void validateNewApplication(
            Event event,
            LocalDateTime now
    ) {

        if (event.getEventStatus() != EventStatus.OPEN) {
            throw new CustomException(
                    ErrorCode.EVENT_NOT_OPEN
            );
        }

        LocalDateTime start = event.getRegistStartDate();
        LocalDateTime deadline = event.getRegistDeadline();

        if (start == null
                || deadline == null
                || !start.isBefore(deadline)) {

            throw configurationError();
        }

        if (now.isBefore(start)) {
            throw new CustomException(
                    ErrorCode.EVENT_REGISTRATION_NOT_STARTED
            );
        }

        if (!now.isBefore(deadline)) {
            throw new CustomException(
                    ErrorCode.EVENT_REGISTRATION_CLOSED
            );
        }
    }

    /**
     * 참가자 생년월일과 보호자 정책을 검증한다.
     *
     * 반환한 LocalDate를 종목·기념품 검증에서도 재사용한다.
     *
     * 단체 신청의 input에는 참가자 birth와
     * 단체장 guardianName / guardianConsent를 전달한다.
     */
    public LocalDate validateParticipant(
            Event event,
            EventRegistrationPolicy policy,
            RegistrationPolicyInput input,
            LocalDate applicationDate
    ) {

        if (policy == null
                || policy.getEvent() == null
                || !event.getId().equals(policy.getEvent().getId())
                || policy.getGuardianRequiredBirthFrom() == null) {

            throw configurationError();
        }

        LocalDate birth = parseBirth(
                input.birth(),
                applicationDate
        );

        boolean guardianRequired =
                !birth.isBefore(
                        policy.getGuardianRequiredBirthFrom()
                );

        if (guardianRequired) {

            if (input.guardianName() == null
                    || input.guardianName().isBlank()) {

                throw new CustomException(
                        ErrorCode.GUARDIAN_NAME_REQUIRED
                );
            }

            if (!Boolean.TRUE.equals(input.guardianConsent())) {
                throw new CustomException(
                        ErrorCode.GUARDIAN_CONSENT_REQUIRED
                );
            }
        }

        return birth;
    }

    /**
     * yyyy-MM-dd 형식의 실제 존재하는 날짜만 허용한다.
     *
     * 오늘 출생자는 허용하고 미래 출생일은 차단한다.
     * 공백을 포함하거나 형식이 다른 문자열은 정규화하지 않고 거부한다.
     */
    public LocalDate parseBirth(
            String birthValue,
            LocalDate applicationDate
    ) {

        if (birthValue == null
                || !BIRTH_PATTERN.matcher(birthValue).matches()) {

            throw invalidBirth();
        }

        LocalDate birth;

        try {
            birth = LocalDate.parse(
                    birthValue,
                    BIRTH_FORMATTER
            );
        } catch (DateTimeParseException exception) {
            throw invalidBirth();
        }

        if (birth.getYear() < 1
                || birth.isAfter(applicationDate)) {

            throw invalidBirth();
        }

        return birth;
    }

    /**
     * 종목별 신청 가능 출생일 범위를 검증한다.
     *
     * 정책은 필수이며, 제한 없는 종목은
     * 양쪽 경계가 null인 정책을 등록한다.
     *
     * Category의 Event 귀속·활성 검증은 기존 호출부에서 유지한다.
     */
    public void validateCategoryBirth(
            EventCategory category,
            EventCategoryRegistrationPolicy policy,
            LocalDate birth
    ) {

        if (policy == null
                || policy.getEventCategory() == null
                || !category.getId().equals(
                policy.getEventCategory().getId()
        )) {

            throw configurationError();
        }

        LocalDate from = policy.getAllowedBirthFrom();
        LocalDate to = policy.getAllowedBirthTo();

        validateConfiguredRange(from, to);

        if (!isWithinRange(birth, from, to)) {
            throw new CustomException(
                    ErrorCode.REGISTRATION_CATEGORY_BIRTH_NOT_ALLOWED
            );
        }
    }

    /**
     * 기존 매핑 검증과 사이즈 정규화 이후 호출한다.
     *
     * normalizedSize는 기존 validateAndNormalizeSize()의 반환값이다.
     *
     * 정책이 없으면 추가 사이즈 제한은 없다.
     * 정책이 있으면 출생일 조건에 해당하는 정책을 모두 만족해야 한다.
     */
    public void validateSouvenirSize(
            EventCategorySouvenir mapping,
            List<EventCategorySouvenirPolicy> policies,
            LocalDate birth,
            String normalizedSize
    ) {

        if (mapping == null || mapping.getSouvenir() == null) {
            throw new CustomException(
                    ErrorCode.INVALID_EVENT_CATEGORY_SOUVENIR
            );
        }

        if (policies == null) {
            throw configurationError();
        }

        Set<String> souvenirSizes =
                getSouvenirSizes(mapping.getSouvenir());

        if (normalizedSize == null
                || !souvenirSizes.contains(normalizedSize)) {

            throw new CustomException(
                    ErrorCode.INVALID_SOUVENIR_SIZE
            );
        }

        for (EventCategorySouvenirPolicy policy : policies) {

            if (policy == null
                    || policy.getEventCategorySouvenir() == null
                    || !mapping.getId().equals(
                    policy.getEventCategorySouvenir().getId()
            )) {

                throw configurationError();
            }

            LocalDate from = policy.getBirthFrom();
            LocalDate to = policy.getBirthTo();

            validateConfiguredRange(from, to);

            Set<String> policySizes =
                    parsePolicySizes(policy.getAllowedSizes());

            if (!souvenirSizes.containsAll(policySizes)) {
                throw configurationError();
            }

            if (isWithinRange(birth, from, to)
                    && !policySizes.contains(normalizedSize)) {

                throw new CustomException(
                        ErrorCode.REGISTRATION_SOUVENIR_SIZE_NOT_ALLOWED
                );
            }
        }
    }

    /**
     * null인 경계 방향은 제한하지 않으며 양 끝 날짜를 포함한다.
     */
    private boolean isWithinRange(
            LocalDate birth,
            LocalDate from,
            LocalDate to
    ) {
        return (from == null || !birth.isBefore(from))
                && (to == null || !birth.isAfter(to));
    }

    /**
     * 정책 설정의 날짜 범위 역전을 차단한다.
     */
    private void validateConfiguredRange(
            LocalDate from,
            LocalDate to
    ) {

        if (from != null
                && to != null
                && from.isAfter(to)) {

            throw configurationError();
        }
    }

    /**
     * 기존 기념품 검증과 같은 방식으로 전체 사이즈를 해석한다.
     *
     * sizes가 비어 있으면 FREE로 취급한다.
     */
    private Set<String> getSouvenirSizes(
            Souvenir souvenir
    ) {

        String sizes = souvenir.getSizes();

        if (sizes == null || sizes.isBlank()) {
            return Set.of("FREE");
        }

        Set<String> result =
                Arrays.stream(sizes.split("\\|"))
                        .map(String::trim)
                        .filter(size -> !size.isBlank())
                        .collect(Collectors.toSet());

        if (result.isEmpty()) {
            throw configurationError();
        }

        return result;
    }

    /**
     * 정책의 허용 사이즈는 비어 있을 수 없다.
     * 구분자 사이에 빈 항목이 있는 설정도 거부한다.
     */
    private Set<String> parsePolicySizes(
            String allowedSizes
    ) {

        if (allowedSizes == null || allowedSizes.isBlank()) {
            throw configurationError();
        }

        String[] values = allowedSizes.split("\\|", -1);

        for (String value : values) {
            if (value.isBlank()) {
                throw configurationError();
            }
        }

        return Arrays.stream(values)
                .map(String::trim)
                .collect(Collectors.toSet());
    }

    private CustomException configurationError() {
        return new CustomException(
                ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR
        );
    }

    private CustomException invalidBirth() {
        return new CustomException(
                ErrorCode.INVALID_REGISTRATION_BIRTH
        );
    }
}