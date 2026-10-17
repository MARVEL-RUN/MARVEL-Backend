package kr.co.teambrain.marvelrun.user.common.swagger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Collections;
import java.util.LinkedHashMap;
import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import kr.co.teambrain.marvelrun.user.event.query.dto.RegistrationQueryResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 DTO 스키마 생성과 enum의 JSON 표현 호환성을 DB 없이 검증한다. */
class DiscountMethodSchemaCustomizerTest {
    /** 값별 설명의 원본은 enum이며 lookup을 포함한 할인 응답이 동일 스키마를 참조한다. */
    @Test
    void lookupSchemaUsesDescriptionsFromSharedEnum() {
        // 실제 응답 DTO에서 중첩 할인 스키마까지 생성한다.
        OpenAPI api = new OpenAPI().components(new Components().schemas(
                ModelConverters.getInstance().readAll(RegistrationQueryResponse.class)));
        DiscountMethodSchemaCustomizer customizer = new DiscountMethodSchemaCustomizer();
        customizer.customise(api);
        customizer.customise(api);

        // 반복 생성해도 설명을 누적하지 않고 공통 스키마 한 개를 연결한다.
        Schema<?> method = api.getComponents().getSchemas().get(DiscountMethodSchemaCustomizer.SCHEMA_NAME);
        assertThat(method).isNotNull();
        assertThat(method.getEnum()).hasSize(DiscountModels.Method.values().length);
        for (DiscountModels.Method value : DiscountModels.Method.values()) {
            assertThat(method.getDescription()).contains(value.name(), value.getLabel(), value.getDescription());
        }
        Schema<?> discount = api.getComponents().getSchemas().get("AppliedDiscountResponse");
        assertThat(discount.getProperties().get("method").get$ref())
                .isEqualTo("#/components/schemas/DiscountCalculationMethod");
    }

    /** 문서에 할인 응답이 없으면 무관한 API 그룹을 변경하지 않는다. */
    @Test
    void unrelatedDocumentDoesNotGainDiscountSchema() {
        OpenAPI api = new OpenAPI().components(new Components());
        new DiscountMethodSchemaCustomizer().customise(api);
        assertThat(api.getComponents().getSchemas()).isNull();
    }

    /** 스키마 목록과 필드 목록이 모두 읽기 전용이어도 기존 필드를 보존하며 확장한다. */
    @Test
    void readOnlySchemaAndPropertyMapsCanBeCustomizedRepeatedly() {
        // 변환기 구현과 무관하게 두 Map을 명시적으로 수정 불가능하게 고정한다.
        OpenAPI api = new OpenAPI().components(new Components().schemas(
                Collections.unmodifiableMap(new LinkedHashMap<>(
                        ModelConverters.getInstance().readAll(RegistrationQueryResponse.class)))));
        Schema<?> discount = api.getComponents().getSchemas().get("AppliedDiscountResponse");
        discount.setProperties(Collections.unmodifiableMap(new LinkedHashMap<>(discount.getProperties())));
        int originalPropertyCount = discount.getProperties().size();

        // 반복 적용과 기존 할인 응답 필드의 보존을 함께 확인한다.
        DiscountMethodSchemaCustomizer customizer = new DiscountMethodSchemaCustomizer();
        customizer.customise(api);
        customizer.customise(api);
        assertThat(api.getComponents().getSchemas()).containsKey(DiscountMethodSchemaCustomizer.SCHEMA_NAME);
        assertThat(discount.getProperties()).hasSize(originalPropertyCount).containsKey("ratePercent");
        assertThat(discount.getProperties().get("method").get$ref())
                .isEqualTo("#/components/schemas/DiscountCalculationMethod");
    }

    /** 설명 필드가 늘어나도 API와 저장 JSON은 enum 이름 문자열로 유지한다. */
    @Test
    void enumDescriptionsDoNotChangeJsonRepresentation() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (DiscountModels.Method method : DiscountModels.Method.values()) {
            String json = mapper.writeValueAsString(method);
            assertThat(json).isEqualTo("\"" + method.name() + "\"");
            assertThat(mapper.readValue(json, DiscountModels.Method.class)).isEqualTo(method);
        }
    }
}
