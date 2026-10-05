package kr.co.teambrain.marvelrun.admin.event.command.application.exception;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.OfflineRegistrationImportResponse;
import java.util.List;

/** 파일 전체 저장을 중단하면서 수집된 행별 오류를 보존한다. */
public class OfflineRegistrationImportException extends CustomException {
    private final OfflineRegistrationImportResponse response;

    /** 기존 업무 예외 체계에 오류 목록과 제외 건수를 연결한다. */
    public OfflineRegistrationImportException(int canceledCount,
            List<OfflineRegistrationImportResponse.Failure> errors) {
        super(ErrorCode.OFFLINE_REGISTRATION_IMPORT_VALIDATION_FAILED);
        this.response = new OfflineRegistrationImportResponse(getErrorCode().name(), 0,
                canceledCount, List.of(), errors);
    }

    /** 예외 처리기에 불변 응답을 전달한다. */
    public OfflineRegistrationImportResponse getResponse() {
        return response;
    }
}
