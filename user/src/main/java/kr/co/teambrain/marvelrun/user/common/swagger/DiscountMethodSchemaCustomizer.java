package kr.co.teambrain.marvelrun.user.common.swagger;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/** 공통 enum의 설명을 사용자 API 문서로 연결하며 업무 모듈의 Swagger 의존을 방지한다. */
@Component
public class DiscountMethodSchemaCustomizer implements OpenApiCustomizer {
    public static final String SCHEMA_NAME = "DiscountCalculationMethod";

    /** 할인 응답이 있는 문서에만 계산 방식의 공통 스키마와 값별 설명을 제공한다. */
    @Override
    public void customise(OpenAPI openApi) {
        // 다른 API 그룹의 문서에는 불필요한 할인 스키마를 추가하지 않는다.
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        Schema<?> discount = openApi.getComponents().getSchemas().get("AppliedDiscountResponse");
        if (discount == null || discount.getProperties() == null || !discount.getProperties().containsKey("method")) {
            return;
        }

        // 값과 설명을 동일 enum에서 생성하며 실제 응답의 문자열 표현은 변경하지 않는다.
        StringSchema method = new StringSchema();
        method.setEnum(Arrays.stream(DiscountModels.Method.values()).map(Enum::name).toList());
        method.setDescription("할인 계산 방식\n\n" + Arrays.stream(DiscountModels.Method.values())
                .map(value -> "- `" + value.name() + "` (" + value.getLabel() + "): " + value.getDescription())
                .collect(Collectors.joining("\n")));
        // 변환기가 반환한 읽기 전용 Map도 처리하도록 순서를 유지한 수정 가능 사본으로 교체한다.
        openApi.getComponents().setSchemas(new LinkedHashMap<>(openApi.getComponents().getSchemas()));
        discount.setProperties(new LinkedHashMap<>(discount.getProperties()));
        openApi.getComponents().addSchemas(SCHEMA_NAME, method);
        discount.addProperty("method", new Schema<String>().$ref("#/components/schemas/" + SCHEMA_NAME));
    }
}
