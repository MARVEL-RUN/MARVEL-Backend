package kr.co.teambrain.marvelrun.admin.event.command.application.service;

import kr.co.teambrain.marvelrun.admin.capacity.command.application.service.OfflineRegistrationCapacityService;
import kr.co.teambrain.marvelrun.admin.event.command.application.context.OfflineRegistrationContext;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.*;
import kr.co.teambrain.marvelrun.admin.event.command.application.excel.OfflineRegistrationExcelReader;
import kr.co.teambrain.marvelrun.admin.event.command.application.exception.OfflineRegistrationImportException;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.OfflineRegistrationImportValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.ConcurrencyFailureException;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 엑셀 전체 오류 수집 후에만 외부 결제 적재 트랜잭션을 시작한다. */
@Service
@RequiredArgsConstructor
public class OfflineRegistrationImportService {
    private final OfflineRegistrationExcelReader reader;
    private final OfflineRegistrationImportValidator validator;
    private final OfflineRegistrationCapacityService capacities;
    private final OfflineRegistrationPersistenceService persistence;
    private final PasswordEncoder passwordEncoder;

    /** 명시적인 KST 승인일자와 파일을 받아 외부 결제 개인 신청을 등록한다. */
    public OfflineRegistrationImportResponse importOfflinePaidRegistrations(String eventId,
            LocalDate paymentDate, MultipartFile file) {
        // 매핑 시트는 작게 보관하고 참가자는 100행마다 해석·검증한다.
        OfflineRegistrationImportResult result = new OfflineRegistrationImportResult();
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        if (paymentDate == null || paymentDate.isAfter(now.toLocalDate())) {
            result.addError(0, "paymentDate", "INVALID_PAYMENT_DATE", "승인일자는 오늘 이전의 실제 날짜여야 합니다.");
            throw new OfflineRegistrationImportException(0, result.errors());
        }
        List<OfflineRegistrationExcelRow> mappingRows = new ArrayList<>();
        OfflineRegistrationExcelMapping[] mapping = new OfflineRegistrationExcelMapping[1];
        boolean[] initialized = {false};
        boolean[] headerSeen = {false};
        reader.readOfflineRegistrationWorkbook(file, mappingRows::addAll, rows -> {
            if (!initialized[0]) {
                mapping[0] = validator.parseOfflineMapping(eventId, mappingRows, result);
                initialized[0] = true;
            }
            rows.stream().filter(row -> row.rowNumber() == 6).forEach(row -> {
                headerSeen[0] = true;
                validateOfflineHeaders(row, result);
            });
            validator.validateOfflineRows(mapping[0], paymentDate, rows, result);
        });
        if (!headerSeen[0]) { result.addError(0, "header", "INVALID_HEADER", "V3 입력 헤더가 없습니다."); }

        // 형식 오류가 있더라도 검증 완료 행의 중복과 부족 수량은 함께 수집한다.
        validator.validateOfflineDuplicates(eventId, result.contexts(), result);
        capacities.collectOfflineCapacityErrors(eventId, result.contexts(), result, false);
        if (!result.errors().isEmpty()) {
            throw new OfflineRegistrationImportException(result.canceledCount(), result.errors());
        }
        if (result.contexts().isEmpty()) {
            return new OfflineRegistrationImportResponse("SUCCESS", 0, result.canceledCount(), List.of(), List.of());
        }

        // 비용이 큰 비밀번호 해시는 DB 잠금을 잡기 전에 계산한다.
        Map<Integer, String> hashes = new HashMap<>();
        for (OfflineRegistrationContext context : result.contexts()) {
            hashes.put(context.rowNumber(), passwordEncoder.encode(context.birth().format(DateTimeFormatter.BASIC_ISO_DATE)));
        }
        try {
            return persistence.persistOfflinePaidRegistrations(eventId, result, hashes, now);
        } catch (DataIntegrityViolationException | ConcurrencyFailureException exception) {
            // 이미 종료된 저장 트랜잭션 밖에서 충돌을 업무 오류로 변환한다.
            throw new CustomException(ErrorCode.CONCURRENT_MODIFICATION);
        }
    }

    /** 열 삽입이나 이전 양식을 현재 V3로 오인하지 않도록 입력 헤더를 확인한다. */
    private void validateOfflineHeaders(OfflineRegistrationExcelRow row, OfflineRegistrationImportResult result) {
        List<String> headers = List.of("번호", "생년월일 *\n숫자 8자리", "이름 *", "전화번호 *", "성별 *", "주소 *", "상세주소 *",
                "종목 *", "기념품 (자동)", "사이즈 *", "보호자 관계", "보호자명", "보호자 연락처", "보호자 동의",
                "필수 약관 동의 *", "마케팅 활용 및 광고성 정보 수신 동의 (선택)", "이메일 (선택)", "현장 처리 상태 *",
                "승인시각(HHmmss) *", "비고 (선택)");
        for (int index = 0; index < headers.size(); index++) {
            String column = Character.toString((char) ('A' + index));
            if (!headers.get(index).equals(row.cellValue(column)) || row.formulaColumns().contains(column)) {
                result.addError(6, column, "INVALID_HEADER", "V3 열 제목과 순서를 유지해주세요.");
            }
        }
    }
}
