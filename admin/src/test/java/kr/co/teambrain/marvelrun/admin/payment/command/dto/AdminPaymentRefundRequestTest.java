package kr.co.teambrain.marvelrun.admin.payment.command.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Collections;
import java.util.List;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 두 관리자 환불 요청의 바인딩·대상 경계만 검증하고 기존 금융 배분 테스트를 중복하지 않는다. */
class AdminPaymentRefundRequestTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 검증 제공자의 자원을 테스트 클래스 종료 시 반환한다. */
    @AfterAll
    static void closeValidator() { FACTORY.close(); }

    /** 개인·단체원 직접 선택과 단체 전체 선택을 동시에 전달할 수 있다. */
    @Test
    void acceptsMixedFullRefundSelection() throws Exception {
        AdminPaymentRefundRequest request = mapper.readValue("""
                {"requestId":"request-1","reason":"참가 취소",
                 "registrationIds":["r1","r2"],"organizationIds":["o1"]}
                """, AdminPaymentRefundRequest.class);
        assertThat(VALIDATOR.validate(request)).isEmpty();
        assertThat(request.organizationIds()).containsExactly("o1");
    }

    /** 빈 대상과 합계 초과를 거절하되 허용 상한은 수락한다. */
    @Test
    void validatesCombinedSelectionLimit() {
        assertThat(VALIDATOR.validate(new AdminPaymentRefundRequest("q", "사유", List.of(), List.of()))).isNotEmpty();
        assertThat(VALIDATOR.validate(new AdminPaymentRefundRequest("q", "사유", Collections.nCopies(100, "r"), List.of()))).isEmpty();
        assertThat(VALIDATOR.validate(new AdminPaymentRefundRequest("q", "사유", Collections.nCopies(100, "r"), List.of("o")))).isNotEmpty();
    }

    /** 외부 입력에서 null·공백 식별자와 중첩 기념품 오류를 검출한다. */
    @Test
    void rejectsInvalidIdentifiersAndNestedSouvenirs() {
        assertThat(VALIDATOR.validate(new AdminPaymentRefundRequest(" ", "사유", List.of("r"), List.of()))).isNotEmpty();
        assertThat(VALIDATOR.validate(new AdminPaymentRefundRequest("q", "사유", null, List.of()))).isNotEmpty();
        AdminPaymentPartialRefundTarget target = new AdminPaymentPartialRefundTarget("r", "c", List.of(new SouvenirJson(" ", "M")), true);
        assertThat(VALIDATOR.validate(new AdminPaymentPartialRefundRequest("q", "사유", List.of(target)))).isNotEmpty();
    }

    /** 같은 참가자를 두 번 수정하도록 입력하면 어느 지시를 적용할지 추측하지 않고 거절한다. */
    @Test
    void rejectsDuplicatePartialRefundTargets() {
        AdminPaymentPartialRefundTarget first = new AdminPaymentPartialRefundTarget("r", "c1", List.of(new SouvenirJson("s", "M")), true);
        AdminPaymentPartialRefundTarget second = new AdminPaymentPartialRefundTarget("r", "c2", List.of(new SouvenirJson("s", "M")), true);
        assertThat(VALIDATOR.validate(new AdminPaymentPartialRefundRequest("q", "사유", List.of(first, second)))).isNotEmpty();
    }

    /** 금액 없이 종목 변경 후보를 받고 0원 유지 기본값과 명시적 거부값을 보존한다. */
    @Test
    void bindsPolicyInputsAndZeroContractSetting() throws Exception {
        AdminPaymentPartialRefundRequest request = mapper.readValue("""
                {"requestId":"q","reason":"종목 변경","targets":[
                  {"registrationId":"r1","eventCategoryId":"c1","selectedSouvenirList":[{"souvenirId":"s","selectedSize":"M"}]},
                  {"registrationId":"r2","eventCategoryId":"c2","selectedSouvenirList":[{"souvenirId":"s","selectedSize":"L"}],"keepParticipationWhenZero":false}]}
                """, AdminPaymentPartialRefundRequest.class);
        assertThat(VALIDATOR.validate(request)).isEmpty();
        assertThat(request.targets().get(0).keepParticipationWhenZero()).isTrue();
        assertThat(request.targets().get(1).keepParticipationWhenZero()).isFalse();
        assertThat(mapper.writeValueAsString(request)).doesNotContain("uniqueRegistrationSelection", "refundAmount", "contractAmount");
    }
}
