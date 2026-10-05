package kr.co.teambrain.marvelrun.admin.event.command.application.dto;

import kr.co.teambrain.marvelrun.admin.event.command.application.context.OfflineRegistrationContext;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.OfflineRegistrationImportResponse.Failure;
import java.util.ArrayList;
import java.util.List;

/** 한 파일의 오류와 검증 완료 입력을 누적하며 요청 간 공유하지 않는다. */
public class OfflineRegistrationImportResult {
    private final List<Failure> errors = new ArrayList<>();
    private final List<OfflineRegistrationContext> contexts = new ArrayList<>();
    private int canceledCount;

    /** 원문 개인정보 없이 오류를 추가한다. */
    public void addError(int row, String field, String code, String message) {
        errors.add(new Failure(row, field, code, message));
    }
    /** 검증 완료 참가자를 누적한다. */
    public void addRegistrationContext(OfflineRegistrationContext context) { contexts.add(context); }
    /** 당일 취소 제외 건수를 누적한다. */
    public void recordCanceledRow() { canceledCount++; }
    /** 호출자가 누적 오류를 조회한다. */
    public List<Failure> errors() { return List.copyOf(errors); }
    /** 행마다 목록 복사 없이 오류 발생 여부를 비교한다. */
    public int errorCount() { return errors.size(); }
    /** 호출자가 검증 완료 입력을 조회한다. */
    public List<OfflineRegistrationContext> contexts() { return List.copyOf(contexts); }
    /** 제외 건수를 반환한다. */
    public int canceledCount() { return canceledCount; }
}
