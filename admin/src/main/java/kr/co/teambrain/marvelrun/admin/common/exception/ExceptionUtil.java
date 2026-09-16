package kr.co.teambrain.marvelrun.admin.common.exception;

import java.util.HashMap;
import java.util.Map;

public class ExceptionUtil {

    public static Map<String, Object> buildMeta(Throwable e) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("originalCause", e.getClass().getSimpleName());
        meta.put("originalMessage", e.getMessage());
        return meta;
    }

    public static CustomException wrap(ErrorCode code, Throwable e) {
        return new CustomException(code);
    }
}
