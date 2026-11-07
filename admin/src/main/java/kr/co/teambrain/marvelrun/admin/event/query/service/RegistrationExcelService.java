package kr.co.teambrain.marvelrun.admin.event.query.service;

import jakarta.persistence.criteria.JoinType;
import jakarta.servlet.http.HttpServletResponse;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationSearchCondition;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationRowQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.SouvenirQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationRowSouvenirs;
import kr.co.teambrain.marvelrun.admin.event.query.util.RegistrationSpecification;
import kr.co.teambrain.marvelrun.admin.user.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventFormat;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 신청 조회 범위에 따라 명단과 한글 신청 상태를 SXSSF 엑셀로 생성한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegistrationExcelService {

    private final RegistrationQueryRepository registrationQueryRepository;
    private final SouvenirQueryRepository souvenirQueryRepository;
    private final RegistrationRowQueryRepository registrationRowQueryRepository;
    private final RegistrationRowSouvenirs rowSouvenirs;
    private final ServerTimeProvider serverTimeProvider;

    private static final int REGISTRATION_CHUNK_SIZE = 1000;

    private static final Pattern CONTROL_CHARACTERS =
            Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]");
    private static final String[] HEADERS = {
            "번호", "신청 ID", "신청유형", "참가자명", "성별", "생년월일", "전화번호",
            "이메일", "대회명", "단체명", "단체장명", "단체장 번호", "종목명", "기념품(사이즈)",
            "신청일시", "계약금액", "신청상태", "우편번호", "주소", "상세주소",
            "보호자명", "보호자 번호", "보호자 관계", "보호자 동의",
            "필수약관 동의", "마케팅 동의", "마케팅 채널 동의", "현재 명단 제외 여부"
    };
    /** 버추얼 "상품" 시트: 구매한 상품마다 한 행이다. 금액은 이 시트에만 있다. */
    private static final String[] LINE_HEADERS = {
            "번호", "신청 ID", "주문자명", "주문자 전화번호", "이메일", "대회명", "상품", "기념품(사이즈)", "상품 금액",
            "신청일시", "신청상태", "우편번호", "주소", "상세주소", "보호자명", "보호자 번호", "보호자 관계",
            "보호자 동의", "필수약관 동의", "마케팅 동의", "마케팅 채널 동의", "현재 명단 제외 여부"
    };
    /** 버추얼 "참가자" 시트: 참가자마다 한 행이다. 금액 열은 없다. */
    private static final String[] PARTICIPANT_HEADERS = {
            "번호", "신청 ID", "상품", "종목명", "참가자명", "성별", "생년월일", "전화번호", "배번",
            "기념품(사이즈)", "주문자명", "주문자 전화번호", "신청일시", "신청상태", "우편번호", "주소",
            "상세주소", "현재 명단 제외 여부"
    };

    /** 선택 ID가 있으면 삭제된 신청도 포함하며, 전체·검색 다운로드는 삭제된 신청을 제외한다. */
    public void download(
            RegistrationSearchCondition condition,
            String organizationId,
            List<String> registrationIds,
            HttpServletResponse response
    ) throws IOException {
        // 대회가 지정된 다운로드만 허용한다.
        if (!StringUtils.hasText(condition.eventId())) {
            throw new CustomException(ErrorCode.EVENT_NOT_FOUND);
        }

        // 버추얼 대회는 참가자 기준 두 시트로 내보낸다. 그 밖의 대회는 기존 신청 단위 한 시트다.
        if (registrationQueryRepository.findEventFormatById(condition.eventId()) == EventFormat.VIRTUAL) {
            downloadVirtual(condition, organizationId, registrationIds, response);
            return;
        }

        // 명시적으로 선택한 신청에만 삭제 여부 제한을 해제한다.
        boolean hasSelectedRegistrations = registrationIds != null && !registrationIds.isEmpty();
        Specification<Registration> specification = RegistrationSpecification.searchWith(condition);
        specification = specification.and((root, query, cb) -> {
            root.fetch("event", JoinType.LEFT);
            root.fetch("user", JoinType.LEFT);
            return hasSelectedRegistrations ? cb.conjunction() : cb.isFalse(root.get("softDeleted"));
        });
        if (StringUtils.hasText(organizationId)) {
            specification = specification.and((root, query, cb) ->
                    cb.equal(root.get("organization").get("id"), organizationId));
        }
        if (hasSelectedRegistrations) {
            specification = specification.and((root, query, cb) ->
                    root.get("id").in(registrationIds));
        }

        // 기존 최신 신청순을 유지하여 신청마다 한 행을 출력한다.
        List<Registration> registrations = registrationQueryRepository.findAll(
                specification,
                Sort.by(Sort.Order.desc("registrationDate"), Sort.Order.desc("id"))
        );
        Map<String, String> souvenirNames = loadSouvenirNames(registrations);
        writeWorkbook(registrations, souvenirNames, response);
    }

    /** 버추얼 대회: "상품" 시트(구매 줄마다 한 행)와 "참가자" 시트(참가자마다 한 행)를 한 파일로 내보낸다. */
    private void downloadVirtual(
            RegistrationSearchCondition condition,
            String organizationId,
            List<String> registrationIds,
            HttpServletResponse response
    ) throws IOException {
        List<RegistrationRowQueryRepository.Row> rows = registrationRowQueryRepository.find(
                condition, new RegistrationRowQueryRepository.Export(organizationId, registrationIds), null, null);
        List<String> ids = rows.stream().map(RegistrationRowQueryRepository.Row::registrationId).distinct().toList();
        // 신청은 ID를 나눠 읽어 IN 목록이 끝없이 길어지지 않게 한다.
        Map<String, Registration> registrations = new HashMap<>();
        for (int from = 0; from < ids.size(); from += REGISTRATION_CHUNK_SIZE) {
            for (Registration registration : registrationQueryRepository.findByIdIn(
                    ids.subList(from, Math.min(from + REGISTRATION_CHUNK_SIZE, ids.size())))) {
                registrations.put(registration.getId(), registration);
            }
        }

        // 구매 줄 기념품은 줄마다 한 번만 읽고(구매 줄이 없으면 신청마다), 이름은 한 번에 조회한다.
        Map<String, List<SouvenirJson>> readLines = new HashMap<>();
        List<List<SouvenirJson>> lineSouvenirs = new ArrayList<>();
        for (RegistrationRowQueryRepository.Row row : rows) {
            String lineKey = row.itemId() != null ? row.itemId() : row.registrationId();
            if (!readLines.containsKey(lineKey)) {
                readLines.put(lineKey, rowSouvenirs.readLine(row.souvenirJson()));
            }
            lineSouvenirs.add(readLines.get(lineKey));
        }
        Map<String, String> souvenirNames = rowSouvenirs.names(new ArrayList<>(readLines.values()));
        Map<String, Set<String>> linkedSouvenirIds = rowSouvenirs.linkedSouvenirIds(condition.eventId());

        // "상품" 시트: 구매 줄 하나에 한 행이다. 줄 금액은 그 줄의 금액이고, 구매 줄이 없는 신청은 계약금액이다.
        List<Object[]> lineValues = new ArrayList<>();
        Set<String> seenItems = new HashSet<>();
        int lineNumber = 1;
        for (int index = 0; index < rows.size(); index++) {
            RegistrationRowQueryRepository.Row row = rows.get(index);
            if (row.itemId() != null && !seenItems.add(row.itemId())) {
                continue;
            }
            Registration registration = registrations.get(row.registrationId());
            Object[] address = addressOf(registration);
            lineValues.add(new Object[]{
                    lineNumber++, registration.getId(), registration.getName(), registration.getPhNum(),
                    emailOf(registration),
                    registration.getEvent() == null ? "" : registration.getEvent().getNameKr(),
                    row.productName(), virtualSouvenirText(lineSouvenirs.get(index), souvenirNames),
                    row.itemId() == null ? registration.getContractAmount() : row.itemAmount(),
                    registration.getRegistrationDate(),
                    registration.getStatus() == null ? "" : registration.getStatus().getDisplayName(),
                    address[0], address[1], address[2], registration.getGuardianName(),
                    registration.getGuardianPhNum(), registration.getGuardianRelationship(),
                    registration.isGuardianConsent(), registration.getTermsEssentialAgreed(),
                    registration.getTermsMarketingAgreed(), registration.getTermsMarketingChannelAgreed(),
                    registration.isSoftDeleted()
            });
        }

        // "참가자" 시트: 목록과 같은 행이며 받는 사람 값을 쓴다. 참가 항목이 없는 행은 주문자 값이다.
        List<Object[]> participantValues = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            RegistrationRowQueryRepository.Row row = rows.get(index);
            Registration registration = registrations.get(row.registrationId());
            Object[] address = addressOf(registration);
            participantValues.add(new Object[]{
                    index + 1, registration.getId(), row.productName(),
                    row.entryCategoryName() != null ? row.entryCategoryName() : row.productName(),
                    row.name(), genderName(row.gender()), row.birth(), row.phNum(),
                    row.bibNumber(),
                    virtualSouvenirText(rowSouvenirs.rowSouvenirs(
                            lineSouvenirs.get(index), row.entryCategoryId(), linkedSouvenirIds), souvenirNames),
                    registration.getName(), registration.getPhNum(),
                    registration.getRegistrationDate(),
                    registration.getStatus() == null ? "" : registration.getStatus().getDisplayName(),
                    address[0], address[1], address[2], registration.isSoftDeleted()
            });
        }
        writeVirtualWorkbook(lineValues, participantValues, response);
    }

    /** 모든 선택 기념품의 이름을 한 번에 조회한다. */
    private Map<String, String> loadSouvenirNames(List<Registration> registrations) {
        Set<String> ids = new LinkedHashSet<>();
        for (Registration registration : registrations) {
            if (registration.getSouvenirJson() == null) {
                continue;
            }
            for (SouvenirJson souvenir : registration.getSouvenirJson()) {
                if (souvenir != null && StringUtils.hasText(souvenir.souvenirId())) {
                    ids.add(souvenir.souvenirId());
                }
            }
        }
        Map<String, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (SouvenirQueryRepository.SouvenirNameProjection souvenir :
                    souvenirQueryRepository.findNamesByIds(new ArrayList<>(ids))) {
                names.put(souvenir.getId(), souvenir.getName());
            }
        }
        return names;
    }

    /** 신청별 한글 상태·명단 제외 여부를 출력하고 금액·일시의 셀 자료형을 보존한다. */
    private void writeWorkbook(
            List<Registration> registrations,
            Map<String, String> souvenirNames,
            HttpServletResponse response
    ) throws IOException {
        // 대용량 출력용 워크북과 열 서식을 준비한다.
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        try (workbook) {
            workbook.setCompressTempFiles(true);
            Sheet sheet = workbook.createSheet("신청목록");
            sheet.createFreezePane(0, 1);
            CellStyle headerStyle = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            CellStyle moneyStyle = workbook.createCellStyle();
            moneyStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
            Row header = sheet.createRow(0);
            for (int index = 0; index < HEADERS.length; index++) {
                Cell cell = header.createCell(index);
                cell.setCellValue(HEADERS[index]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(index, 20 * 256);
            }
            sheet.setColumnWidth(1, 38 * 256);
            sheet.setColumnWidth(13, 36 * 256);
            sheet.setColumnWidth(18, 50 * 256);

            // 조회 순서대로 개인정보와 운영 상태를 행에 기록한다.
            int rowNumber = 1;
            for (Registration registration : registrations) {
                Organization organization = registration.getOrganization();
                String email = emailOf(registration);
                Object[] address = addressOf(registration);
                Object[] values = {
                        rowNumber, registration.getId(), organization == null ? "개인" : "단체",
                        registration.getName(), genderName(registration.getGender()), registration.getBirth(),
                        registration.getPhNum(), email,
                        registration.getEvent() == null ? "" : registration.getEvent().getNameKr(),
                        organization == null ? "" : organization.getGroupName(),
                        organization == null ? "" : organization.getLeaderName(),
                        organization == null ? "" : organization.getLeaderPhNum(),
                        registration.getEventCategory() == null ? "" : registration.getEventCategory().getName(),
                        souvenirText(registration.getSouvenirJson(), souvenirNames),
                        registration.getRegistrationDate(), registration.getContractAmount(),
                        registration.getStatus() == null ? "" : registration.getStatus().getDisplayName(),
                        address[0], address[1], address[2], registration.getGuardianName(),
                        registration.getGuardianPhNum(), registration.getGuardianRelationship(),
                        registration.isGuardianConsent(), registration.getTermsEssentialAgreed(),
                        registration.getTermsMarketingAgreed(), registration.getTermsMarketingChannelAgreed(),
                        registration.isSoftDeleted()
                };
                Row row = sheet.createRow(rowNumber++);
                for (int index = 0; index < values.length; index++) {
                    writeCell(row.createCell(index), values[index], moneyStyle, dateStyle, index == 15);
                }
            }
            // 추가된 열까지 필터를 적용하고 다운로드 응답을 작성한다.
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                    0, registrations.size(), 0, HEADERS.length - 1));
            writeResponse(workbook, response);
        } finally {
            workbook.dispose();
        }
    }

    /** 버추얼 대회: 두 시트를 한 통합 문서에 쓴다. 금액은 "상품" 시트만 가진다. */
    private void writeVirtualWorkbook(List<Object[]> lineValues, List<Object[]> participantValues,
                                      HttpServletResponse response) throws IOException {
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        try (workbook) {
            workbook.setCompressTempFiles(true);
            writeSheet(workbook, "상품", LINE_HEADERS, lineValues, 8,
                    Map.of(1, 38, 6, 36, 7, 36, 12, 50));
            writeSheet(workbook, "참가자", PARTICIPANT_HEADERS, participantValues, -1,
                    Map.of(1, 38, 2, 36, 9, 36, 15, 50));
            writeResponse(workbook, response);
        } finally {
            workbook.dispose();
        }
    }

    /** 시트 하나를 만든다. 첫 행은 굵은 머리글, 금액 열(moneyIndex)만 천 단위 서식이며 -1이면 금액 열이 없다. */
    private void writeSheet(SXSSFWorkbook workbook, String sheetName, String[] headers, List<Object[]> values,
                            int moneyIndex, Map<Integer, Integer> wideColumns) {
        Sheet sheet = workbook.createSheet(sheetName);
        sheet.createFreezePane(0, 1);
        CellStyle headerStyle = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        headerStyle.setFont(font);
        CellStyle moneyStyle = workbook.createCellStyle();
        moneyStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));
        CellStyle dateStyle = workbook.createCellStyle();
        dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
        Row header = sheet.createRow(0);
        for (int index = 0; index < headers.length; index++) {
            Cell cell = header.createCell(index);
            cell.setCellValue(headers[index]);
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(index, wideColumns.getOrDefault(index, 20) * 256);
        }
        for (int rowIndex = 0; rowIndex < values.size(); rowIndex++) {
            Object[] rowValues = values.get(rowIndex);
            Row row = sheet.createRow(rowIndex + 1);
            for (int index = 0; index < rowValues.length; index++) {
                writeCell(row.createCell(index), rowValues[index], moneyStyle, dateStyle, index == moneyIndex);
            }
        }
        // 추가된 열까지 필터를 적용한다.
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                0, values.size(), 0, headers.length - 1));
    }

    /** 다운로드 응답 헤더를 쓰고 워크북을 내보낸다. */
    private void writeResponse(SXSSFWorkbook workbook, HttpServletResponse response) throws IOException {
        String timestamp = serverTimeProvider.currentDateTime()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = URLEncoder.encode("마블런_신청목록_" + timestamp + ".xlsx",
                StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"registrations_" + timestamp + ".xlsx\"; filename*=UTF-8''" + filename);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setDateHeader(HttpHeaders.EXPIRES, 0);
        response.setHeader("X-Content-Type-Options", "nosniff");
        workbook.write(response.getOutputStream());
        response.getOutputStream().flush();
    }

    /** 주문자 이메일이다. 참가자 개별 이메일을 먼저 보고, 없으면 단체장 또는 유저 이메일을 쓴다. */
    private String emailOf(Registration registration) {
        Organization organization = registration.getOrganization();
        String email = registration.getEmail();
        if (!StringUtils.hasText(email)) {
            email = organization != null ? organization.getEmail()
                    : registration.getUser() == null ? "" : registration.getUser().getEmail();
        }
        return email;
    }

    /** 우편번호·주소·상세주소다. 주소 끝의 "_우편번호"는 떼어 따로 적고, 신청 주소가 없으면 단체 주소를 쓴다. */
    private Object[] addressOf(Registration registration) {
        Organization organization = registration.getOrganization();
        String address = registration.getAddress();
        String addressDetail = registration.getAddressDetail();
        if (!StringUtils.hasText(address) && organization != null) {
            address = organization.getAddress();
            addressDetail = organization.getAddressDetail();
        }
        String zipcode = "";
        if (address != null) {
            int separator = address.lastIndexOf('_');
            if (separator >= 0 && address.substring(separator + 1).matches("\\d{5}")) {
                zipcode = address.substring(separator + 1);
                address = address.substring(0, separator);
            }
        }
        return new Object[]{zipcode, address, addressDetail};
    }

    /** 값의 자료형을 보존하고 전화번호·우편번호의 앞자리 0을 유지한다. */
    private void writeCell(Cell cell, Object value, CellStyle moneyStyle, CellStyle dateStyle, boolean money) {
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof LocalDateTime dateTime) {
            cell.setCellValue(dateTime);
            cell.setCellStyle(dateStyle);
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
            if (money) {
                cell.setCellStyle(moneyStyle);
            }
        } else if (value instanceof Boolean agreed) {
            cell.setCellValue(agreed ? "Y" : "N");
        } else {
            cell.setCellValue(clean(value.toString()));
        }
    }

    /** 저장된 성별을 표시명으로 바꾸며 미입력은 빈 셀로 유지한다. */
    private String genderName(GenderClass gender) {
        if (gender == null) {
            return "";
        }
        return switch (gender.name()) {
            case "M" -> "남성";
            case "F" -> "여성";
            default -> gender.name();
        };
    }

    /** 기념품 전체와 사이즈를 결합하고 삭제된 기념품은 ID를 남긴다. */
    private String souvenirText(List<SouvenirJson> souvenirs, Map<String, String> names) {
        if (souvenirs == null) {
            return "";
        }
        return souvenirs.stream().filter(Objects::nonNull)
                .map(souvenir -> clean(names.getOrDefault(souvenir.souvenirId(), souvenir.souvenirId()))
                        + (StringUtils.hasText(souvenir.selectedSize())
                        ? " (" + clean(souvenir.selectedSize()) + ")" : ""))
                .collect(Collectors.joining(" | "));
    }

    /** 버추얼 행의 기념품 칸이다. 구매 줄 JSON을 못 읽었으면(null) 엑셀 전체를 실패시키지 않고 확인 문구를 쓴다. */
    private String virtualSouvenirText(List<SouvenirJson> souvenirs, Map<String, String> names) {
        return souvenirs == null ? "기념품 확인 필요" : souvenirText(souvenirs, names);
    }

    /** XML 제어 문자를 제거하고 엑셀 셀의 최대 문자 수를 지킨다. */
    private String clean(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = CONTROL_CHARACTERS.matcher(value).replaceAll("");
        return cleaned.length() > 32767 ? cleaned.substring(0, 32767) : cleaned;
    }
}
