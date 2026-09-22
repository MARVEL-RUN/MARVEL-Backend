package kr.co.teambrain.marvelrun.admin.event.query.service;

import jakarta.persistence.criteria.JoinType;
import jakarta.servlet.http.HttpServletResponse;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationSearchCondition;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.SouvenirQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.util.RegistrationSpecification;
import kr.co.teambrain.marvelrun.admin.user.command.application.domain.Organization;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** KMA의 SXSSF 출력 방식을 사용해 마블런 신청 정보를 엑셀로 생성한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegistrationExcelService {

    private final RegistrationQueryRepository registrationQueryRepository;
    private final SouvenirQueryRepository souvenirQueryRepository;

    private static final Pattern CONTROL_CHARACTERS =
            Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]");
    private static final String[] HEADERS = {
            "번호", "신청 ID", "신청유형", "참가자명", "성별", "생년월일", "전화번호",
            "이메일", "대회명", "단체명", "단체장명", "단체장 번호", "종목명", "기념품(사이즈)",
            "신청일시", "계약금액", "신청상태", "우편번호", "주소", "상세주소",
            "보호자명", "보호자 번호", "보호자 관계", "보호자 동의",
            "필수약관 동의", "마케팅 동의", "마케팅 채널 동의"
    };

    /** 검색 조건 및 선택 ID를 함께 적용하고 삭제되지 않은 신청만 출력한다. */
    public void download(
            RegistrationSearchCondition condition,
            String organizationId,
            List<String> registrationIds,
            HttpServletResponse response
    ) throws IOException {
        if (!StringUtils.hasText(condition.eventId())) {
            throw new CustomException(ErrorCode.EVENT_NOT_FOUND);
        }

        Specification<Registration> specification = RegistrationSpecification.searchWith(condition);
        specification = specification.and((root, query, cb) -> {
            root.fetch("event", JoinType.LEFT);
            root.fetch("user", JoinType.LEFT);
            return cb.isFalse(root.get("softDeleted"));
        });
        if (StringUtils.hasText(organizationId)) {
            specification = specification.and((root, query, cb) ->
                    cb.equal(root.get("organization").get("id"), organizationId));
        }
        if (registrationIds != null && !registrationIds.isEmpty()) {
            specification = specification.and((root, query, cb) ->
                    root.get("id").in(registrationIds));
        }

        List<Registration> registrations = registrationQueryRepository.findAll(
                specification,
                Sort.by(Sort.Order.desc("registrationDate"), Sort.Order.desc("id"))
        );
        Map<String, String> souvenirNames = loadSouvenirNames(registrations);
        writeWorkbook(registrations, souvenirNames, response);
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

    /** 문자열은 문자 셀로, 금액과 일시는 각각 숫자 및 날짜 셀로 출력한다. */
    private void writeWorkbook(
            List<Registration> registrations,
            Map<String, String> souvenirNames,
            HttpServletResponse response
    ) throws IOException {
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

            int rowNumber = 1;
            for (Registration registration : registrations) {
                Organization organization = registration.getOrganization();
                String email = organization != null ? organization.getEmail()
                        : registration.getUser() == null ? "" : registration.getUser().getEmail();
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
                Object[] values = {
                        rowNumber, registration.getId(), organization == null ? "개인" : "단체",
                        registration.getName(), genderName(registration), registration.getBirth(),
                        registration.getPhNum(), email,
                        registration.getEvent() == null ? "" : registration.getEvent().getNameKr(),
                        organization == null ? "" : organization.getGroupName(),
                        organization == null ? "" : organization.getLeaderName(),
                        organization == null ? "" : organization.getLeaderPhNum(),
                        registration.getEventCategory() == null ? "" : registration.getEventCategory().getName(),
                        souvenirText(registration.getSouvenirJson(), souvenirNames),
                        registration.getRegistrationDate(), registration.getContractAmount(),
                        registration.getStatus() == null ? "" : registration.getStatus().name(),
                        zipcode, address, addressDetail, registration.getGuardianName(),
                        registration.getGuardianPhNum(), registration.getGuardianRelationship(),
                        registration.isGuardianConsent(), registration.getTermsEssentialAgreed(),
                        registration.getTermsMarketingAgreed(), registration.getTermsMarketingChannelAgreed()
                };
                Row row = sheet.createRow(rowNumber++);
                for (int index = 0; index < values.length; index++) {
                    writeCell(row.createCell(index), values[index], moneyStyle, dateStyle, index == 15);
                }
            }
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                    0, registrations.size(), 0, HEADERS.length - 1));
            String timestamp = LocalDateTime.now(ZoneId.of("Asia/Seoul"))
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
        } finally {
            workbook.dispose();
        }
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
    private String genderName(Registration registration) {
        if (registration.getGender() == null) {
            return "";
        }
        return switch (registration.getGender().name()) {
            case "M" -> "남성";
            case "F" -> "여성";
            default -> registration.getGender().name();
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

    /** XML 제어 문자를 제거하고 엑셀 셀의 최대 문자 수를 지킨다. */
    private String clean(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = CONTROL_CHARACTERS.matcher(value).replaceAll("");
        return cleaned.length() > 32767 ? cleaned.substring(0, 32767) : cleaned;
    }
}
