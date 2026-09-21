package kr.co.teambrain.marvelrun.admin.payment.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 저장된 비민감 metadata를 유지하되 내부 ID와 키 필드는 재귀적으로 제외한다. */
@Component
@RequiredArgsConstructor
public class AdminPaymentLogMetadata {
    private final ObjectMapper objectMapper;

    /** MySQL JSON 컬럼을 읽으며 해석 불가 시 원문 대신 오류 표시를 남긴다. */
    public Map<String, Object> read(Object raw) {
        if (raw == null) { return null; }
        try {
            String json = raw instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : raw.toString();
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
            return parsed == null ? null : cleanMap(parsed);
        } catch (JsonProcessingException exception) {
            return Map.of("metadataUnreadable", true);
        }
    }

    /** 필드명 기준으로 개발용 식별자와 인증·민감 필드를 제외한다. */
    private Map<String, Object> cleanMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String name = key.toString();
            String normalized = name.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
            if (normalized.equals("orderid") || !(normalized.equals("id") || normalized.endsWith("ids")
                    || normalized.endsWith("id") || normalized.endsWith("key") || normalized.endsWith("keys")
                    || normalized.contains("password") || normalized.contains("secret")
                    || normalized.contains("authorization") || normalized.contains("token")
                    || normalized.contains("cardnumber") || normalized.contains("accountnumber"))) {
                result.put(name, cleanValue(value));
            }
        });
        return result;
    }

    /** 중첩 객체와 목록에도 동일 제외 규칙을 적용한다. */
    private Object cleanValue(Object value) {
        if (value instanceof Map<?, ?> map) { return cleanMap(map); }
        if (value instanceof List<?> list) { return list.stream().map(this::cleanValue).toList(); }
        return value;
    }
}
