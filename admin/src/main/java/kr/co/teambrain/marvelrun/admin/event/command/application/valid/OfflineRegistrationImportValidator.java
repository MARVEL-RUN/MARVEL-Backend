package kr.co.teambrain.marvelrun.admin.event.command.application.valid;

import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.event.command.application.context.OfflineRegistrationContext;
import kr.co.teambrain.marvelrun.admin.event.command.application.context.RegistrationPolicyContext;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategorySouvenir;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.*;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.dto.RegistrationPolicyInput;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.admin.event.command.repository.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/*
 * 사용자 서버 참조 시각: 2026-10-02 17:10:43 KST
 * 참조 파일: user/src/main/java/kr/co/teambrain/marvelrun/user/event/command/application/valid/RegistrationPolicyValidator.java
 * 유지한 동작: 참가자·종목·기념품 정책은 관리자에 존재하는 동일 책임의 Validator로 검증한다.
 * 관리자 적용 차이: 온라인 접수기간과 Pricing을 호출하지 않고 업로드 매핑 가격을 적용한다.
 */
/** 엑셀 입력 오류를 누적하고 기존 참가 정책을 통과한 저장 Context를 구성한다. */
@Component
@RequiredArgsConstructor
public class OfflineRegistrationImportValidator {
    private static final int MAX_NOTE_BYTES = 65_535;

    private final EventCommandRepository events;
    private final EventCategoryCommandRepository categories;
    private final EventCategorySouvenirCommandRepository mappings;
    private final RegistrationCommandRepository registrations;
    private final RegistrationPolicyLoader policyLoader;
    private final RegistrationPolicyValidator policyValidator;
    private final Validator beanValidator;

    /** 매핑의 명시적 가격과 사이즈만 해석하고 자동 계산 결과는 사용하지 않는다. */
    public OfflineRegistrationExcelMapping parseOfflineMapping(String eventId,
            List<OfflineRegistrationExcelRow> rows, OfflineRegistrationImportResult result) {
        Map<Integer, OfflineRegistrationExcelRow> index = new HashMap<>();
        rows.forEach(row -> index.put(row.rowNumber(), row));
        try {
            // V3 구조와 매핑의 상호 관계를 먼저 확인한다.
            if (!eventId.equals(mappingValue(index, 2, "B"))
                    || !"3.0".equals(mappingValue(index, 6, "B"))) {
                throw new IllegalArgumentException();
            }
            LocalDate cutoff = LocalDate.parse(mappingValue(index, 3, "B"), DateTimeFormatter.BASIC_ISO_DATE);
            String souvenirId = mappingValue(index, 4, "B");
            String souvenirName = mappingValue(index, 5, "B");
            Map<String, OfflineRegistrationExcelMapping.Price> prices = new LinkedHashMap<>();
            Set<String> categoryIds = new HashSet<>();
            for (int row = 9; row <= 11; row++) {
                String name = mappingValue(index, row, "A");
                String id = mappingValue(index, row, "B");
                BigDecimal adult = parseMappingAmount(mappingValue(index, row, "C"));
                String childValue = mappingValue(index, row, "D");
                BigDecimal child = "참가 불가".equals(childValue) ? null : parseMappingAmount(childValue);
                if (!categoryIds.add(id) || prices.putIfAbsent(name,
                        new OfflineRegistrationExcelMapping.Price(id, adult, child)) != null
                        || !id.equals(mappingValue(index, row + 14, "A"))
                        || !souvenirId.equals(mappingValue(index, row + 14, "B"))) {
                    throw new IllegalArgumentException();
                }
            }
            Set<String> adultSizes = new LinkedHashSet<>();
            Set<String> childSizes = new LinkedHashSet<>();
            for (int row = 14; row <= 20; row++) {
                adultSizes.add(mappingValue(index, row, "C"));
                if (row <= 15) {
                    childSizes.add(mappingValue(index, row, "D"));
                }
            }
            return new OfflineRegistrationExcelMapping(eventId, cutoff, souvenirId,
                    souvenirName, prices, adultSizes, childSizes);
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            result.addError(0, "mapping", "INVALID_MAPPING", "대회·V3 버전·가격·종목·사이즈 매핑을 확인해주세요.");
            return null;
        }
    }

    /** 필수 매핑 셀은 수식 대신 명시적인 값이어야 한다. */
    private String mappingValue(Map<Integer, OfflineRegistrationExcelRow> rows, int row, String column) {
        OfflineRegistrationExcelRow source = rows.get(row);
        if (source == null || source.formulaColumns().contains(column) || source.cellValue(column).isBlank()) {
            throw new IllegalArgumentException();
        }
        return source.cellValue(column).trim();
    }

    /** 현장 카드 금액은 양의 원화 정수이며 원장 decimal 범위를 넘지 않아야 한다. */
    private BigDecimal parseMappingAmount(String value) {
        BigDecimal amount = new BigDecimal(value);
        if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0
                || amount.compareTo(new BigDecimal("9999999999")) > 0) {
            throw new IllegalArgumentException();
        }
        return amount;
    }

    /** 한 배치의 독립 입력 오류를 끝까지 수집하고 정상 행에 DB 정책을 적용한다. */
    @Transactional(readOnly = true)
    public void validateOfflineRows(OfflineRegistrationExcelMapping mapping, LocalDate paymentDate,
            List<OfflineRegistrationExcelRow> rows, OfflineRegistrationImportResult result) {
        // 배치에서 사용하는 종목과 정책은 같은 조회 결과를 공유한다.
        Map<String, EventCategory> categoryCache = new HashMap<>();
        Map<String, List<EventCategorySouvenir>> souvenirCache = new HashMap<>();
        Map<String, RegistrationPolicyContext> policyCache = new HashMap<>();
        Event event = mapping == null ? null : events.findById(mapping.eventId()).orElse(null);
        for (OfflineRegistrationExcelRow row : rows) {
            if (row.rowNumber() < 7 || isDefaultRow(row)) { continue; }
            if (row.rowNumber() > 1006) {
                result.addError(row.rowNumber(), "row", "ROW_LIMIT", "1,000명 입력 범위를 초과했습니다.");
                continue;
            }
            if ("당일취소".equals(row.cellValue("R")) && !row.formulaColumns().contains("R")) {
                result.recordCanceledRow();
                continue;
            }
            OfflineRegistrationContext context = validateRowFields(mapping, paymentDate, row, result);
            if (context == null) { continue; }
            if (event == null) {
                result.addError(row.rowNumber(), "eventId", "EVENT_NOT_FOUND", "대회를 찾을 수 없습니다.");
                continue;
            }

            // 업무 오류만 행 오류로 변환하고 DB 장애는 호출부로 전달한다.
            try {
                EventCategory category = categoryCache.computeIfAbsent(context.categoryId(),
                        id -> categories.findById(id).orElse(null));
                List<EventCategorySouvenir> selected = souvenirCache.computeIfAbsent(context.categoryId(),
                        mappings::findAllMappingsByCategoryId);
                if (category == null || !event.getId().equals(category.getEvent().getId())
                        || selected.size() != 1 || !mapping.souvenirId().equals(selected.getFirst().getSouvenir().getId())
                        || !event.getId().equals(selected.getFirst().getSouvenir().getEvent().getId())) {
                    result.addError(row.rowNumber(), "category", "INVALID_MAPPING", "대회·종목·기념품 소속과 매핑을 확인해주세요.");
                    continue;
                }
                EventCategorySouvenir souvenir = selected.getFirst();
                if (!Boolean.TRUE.equals(category.getIsActive()) || !Boolean.TRUE.equals(souvenir.getSouvenir().getIsActive())) {
                    result.addError(row.rowNumber(), "category", "INACTIVE_SELECTION", "비활성 종목 또는 기념품은 신청할 수 없습니다.");
                    continue;
                }
                RegistrationPolicyContext policies = policyCache.computeIfAbsent(context.categoryId(),
                        id -> policyLoader.load(event.getId(), Set.of(id), Set.of(souvenir.getId())));
                policyValidator.validateParticipant(event, policies.eventPolicy(),
                        new RegistrationPolicyInput(context.birth().toString(), context.guardianName(), context.guardianConsent()),
                        paymentDate);
                policyValidator.validateCategoryBirth(category, policies.categoryPolicies().get(category.getId()), context.birth());
                policyValidator.validateSouvenirSize(souvenir, policies.souvenirPolicies().get(souvenir.getId()),
                        context.birth(), row.cellValue("J").trim());
                result.addRegistrationContext(context);
            } catch (CustomException exception) {
                result.addError(row.rowNumber(), "policy", exception.getErrorCode().name(), exception.getErrorCode().getMessage());
            }
        }
    }

    /** 빈 양식에 미리 채운 상세주소와 동의 기본값은 입력으로 세지 않는다. */
    private boolean isDefaultRow(OfflineRegistrationExcelRow row) {
        for (String column : List.of("B", "C", "D", "E", "F", "H", "J", "K", "L", "M", "Q", "R", "S", "T")) {
            if (!row.cellValue(column).isBlank() || row.formulaColumns().contains(column)) { return false; }
        }
        return (row.cellValue("G").isBlank() || "없음".equals(row.cellValue("G")))
                && List.of("N", "O", "P").stream().allMatch(column ->
                row.cellValue(column).isBlank() || "N".equals(row.cellValue(column)));
    }

    /** 독립적인 셀 오류를 모두 모은 후 정상 입력만 저장 Context로 변환한다. */
    private OfflineRegistrationContext validateRowFields(OfflineRegistrationExcelMapping mapping,
            LocalDate paymentDate, OfflineRegistrationExcelRow row, OfflineRegistrationImportResult result) {
        int before = result.errorCount();
        for (String column : List.of("B", "C", "D", "E", "F", "G", "H", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T")) {
            if (row.formulaColumns().contains(column)) {
                result.addError(row.rowNumber(), column, "FORMULA_INPUT", "입력 칸에는 수식 대신 값을 기입해주세요.");
            }
        }
        validateText(row, "C", 50, true, result);
        validateText(row, "F", 300, true, result);
        validateText(row, "G", 255, true, result);
        validateText(row, "Q", 255, false, result);
        // 실제 note TEXT 컬럼은 문자 수가 아닌 저장 바이트 수로 제한한다.
        if (row.cellValue("T").getBytes(StandardCharsets.UTF_8).length > MAX_NOTE_BYTES) {
            result.addError(row.rowNumber(), "note", "NOTE_TOO_LONG",
                    "비고는 UTF-8 기준 65,535바이트 이내로 입력해주세요.");
        }
        validatePhone(row, "D", true, result);
        LocalDate birth = null;
        LocalTime time = null;
        try {
            if (!row.cellValue("B").matches("[0-9]{8}")) { throw new IllegalArgumentException(); }
            birth = LocalDate.parse(row.cellValue("B"), DateTimeFormatter.BASIC_ISO_DATE);
            if (birth.getYear() < 1900 || birth.isAfter(paymentDate)) { throw new IllegalArgumentException(); }
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            result.addError(row.rowNumber(), "birth", "INVALID_BIRTH", "실제 생년월일을 숫자 8자리로 입력해주세요.");
        }
        try {
            String text = row.cellValue("S");
            if (!text.matches("[0-9]{6}")) { throw new IllegalArgumentException(); }
            time = LocalTime.of(Integer.parseInt(text.substring(0, 2)), Integer.parseInt(text.substring(2, 4)),
                    Integer.parseInt(text.substring(4, 6)));
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            result.addError(row.rowNumber(), "approvalTime", "INVALID_APPROVAL_TIME", "승인시각을 HHmmss 숫자 6자리로 입력해주세요.");
        }
        if (!Set.of("남성", "여성").contains(row.cellValue("E"))) {
            result.addError(row.rowNumber(), "gender", "INVALID_GENDER", "성별을 선택해주세요.");
        }
        if (!"결제완료".equals(row.cellValue("R"))) {
            result.addError(row.rowNumber(), "status", "INVALID_STATUS", "현장 처리 상태를 선택해주세요.");
        }
        for (String column : List.of("N", "O", "P")) {
            if (!Set.of("Y", "N").contains(row.cellValue(column))) {
                result.addError(row.rowNumber(), column, "INVALID_CONSENT", "동의는 Y 또는 N이어야 합니다.");
            }
        }
        if (!"Y".equals(row.cellValue("O"))) {
            result.addError(row.rowNumber(), "essentialConsent", "REQUIRED", "필수 약관 동의가 필요합니다.");
        }
        if (!beanValidator.validate(new EmailInput(row.cellValue("Q"))).isEmpty()) {
            result.addError(row.rowNumber(), "email", "INVALID_EMAIL", "이메일 형식을 확인해주세요.");
        }

        // 가격과 어린이 입력 조건은 명시적인 매핑에 따라 선택한다.
        boolean child = mapping != null && birth != null && !birth.isBefore(mapping.childCutoff());
        validateText(row, "K", 50, child, result);
        validateText(row, "L", 50, child, result);
        validatePhone(row, "M", child, result);
        if (child && !"Y".equals(row.cellValue("N"))) {
            result.addError(row.rowNumber(), "guardianConsent", "REQUIRED", "어린이는 보호자 동의가 필요합니다.");
        }
        if (mapping == null) { return null; }
        OfflineRegistrationExcelMapping.Price price = mapping.prices().get(row.cellValue("H").trim());
        BigDecimal amount = price == null ? null : (child ? price.childAmount() : price.adultAmount());
        if (amount == null) {
            result.addError(row.rowNumber(), "category", "INVALID_CATEGORY", "참가 가능한 종목과 가격 매핑을 확인해주세요.");
        }
        if (!(child ? mapping.childSizes() : mapping.adultSizes()).contains(row.cellValue("J").trim())) {
            result.addError(row.rowNumber(), "size", "INVALID_SIZE", "참가 구분에 맞는 사이즈를 선택해주세요.");
        }
        if (result.errorCount() != before) { return null; }
        return new OfflineRegistrationContext(row.rowNumber(), row.cellValue("C").trim(), birth,
                row.cellValue("D").replace("-", ""), "남성".equals(row.cellValue("E")) ? GenderClass.M : GenderClass.F,
                row.cellValue("F").trim(), row.cellValue("G").trim(), price.categoryId(),
                List.of(new SouvenirJson(mapping.souvenirId(), row.cellValue("J").trim())),
                row.cellValue("K").trim(), row.cellValue("L").trim(), row.cellValue("M").replace("-", ""),
                "Y".equals(row.cellValue("N")), "Y".equals(row.cellValue("P")), row.cellValue("Q").trim(),
                row.cellValue("T"), amount, LocalDateTime.of(paymentDate, time), child);
    }

    /** 문자열의 필수 여부와 저장 길이를 검사한다. */
    private void validateText(OfflineRegistrationExcelRow row, String column, int max, boolean required,
            OfflineRegistrationImportResult result) {
        String value = row.cellValue(column).trim();
        if ((required && value.isBlank()) || value.length() > max) {
            result.addError(row.rowNumber(), column, "INVALID_TEXT", "필수 입력과 최대 " + max + "자 제한을 확인해주세요.");
        }
    }

    /** 하이픈을 제외한 국내 전화번호의 기본 형식을 검증한다. */
    private void validatePhone(OfflineRegistrationExcelRow row, String column, boolean required,
            OfflineRegistrationImportResult result) {
        String value = row.cellValue(column);
        if ((!value.isEmpty() || required) && (value.length() > 14 || !value.matches("[0-9-]+")
                || !value.replace("-", "").matches("0[0-9]{8,10}"))) {
            result.addError(row.rowNumber(), column, "INVALID_PHONE", "전화번호를 하이픈 제외 9~11자리로 입력해주세요.");
        }
    }

    /** 파일 내부와 DB의 중복을 전체 참가자 기준으로 수집한다. */
    @Transactional(readOnly = true)
    public void validateOfflineDuplicates(String eventId, List<OfflineRegistrationContext> contexts,
            OfflineRegistrationImportResult result) {
        Map<List<String>, List<OfflineRegistrationContext>> grouped = new LinkedHashMap<>();
        for (OfflineRegistrationContext context : contexts) {
            grouped.computeIfAbsent(List.of(context.name(), context.phone(), context.birth().toString()),
                    key -> new ArrayList<>()).add(context);
        }
        for (List<OfflineRegistrationContext> group : grouped.values()) {
            if (group.size() > 1) {
                group.forEach(context -> result.addError(context.rowNumber(), "participant", "DUPLICATE_IN_FILE", "파일 내 동일 참가자가 있습니다."));
            }
        }
        for (int start = 0; start < contexts.size(); start += 100) {
            List<OfflineRegistrationContext> batch = contexts.subList(start, Math.min(start + 100, contexts.size()));
            List<String> names = batch.stream().map(OfflineRegistrationContext::name).distinct().toList();
            registrations.findActiveByEventAndNames(eventId, names).forEach(existing -> {
                List<OfflineRegistrationContext> matches = grouped.get(List.of(existing.getName(),
                        existing.getPhNum().replace("-", ""), existing.getBirth()));
                if (matches != null) {
                    matches.forEach(context -> result.addError(context.rowNumber(), "participant", "DUPLICATE_REGISTRATION", "이미 신청한 참가자입니다."));
                }
            });
        }
    }

    /** 선택 이메일은 기존 Bean Validation 의미를 사용한다. */
    private record EmailInput(@Email String value) { }
}
