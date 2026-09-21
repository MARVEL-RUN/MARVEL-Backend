package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationModificationParticipantRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationModificationClassifier.Change.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 저장값과 요청만으로 분류하며 정책 변경 재검증이나 엔티티 변경이 없는지 확인한다. */
class RegistrationModificationClassifierTest {
    private final RegistrationModificationClassifier classifier = new RegistrationModificationClassifier();

    /** 정책에 사용되지 않는 개인정보 각각을 전체 수정으로 보내지 않는다. */
    @ParameterizedTest
    @MethodSource("personalChanges")
    void personalInformationFields(String field, Object value) throws Exception {
        assertThat(classifier.classifyPersonal(current("r1"), replace(request(), field, value)))
                .isEqualTo(PERSONAL_INFORMATION);
    }

    /** 개인정보 필드의 변경 및 null 삭제 후보를 제공한다. */
    static Stream<Arguments> personalChanges() {
        return Stream.of(
                Arguments.of("name", "정정 이름"),
                Arguments.of("phNum", "010-2222-3333"),
                Arguments.of("gender", GenderClass.F),
                Arguments.of("address", "정정 주소"),
                Arguments.of("addressDetail", "정정 상세주소"),
                Arguments.of("address", null),
                Arguments.of("addressDetail", null));
    }

    /** 결과 나이·가격을 조회하지 않고 영향 입력 변경 자체로 전체 수정을 판정한다. */
    @ParameterizedTest
    @MethodSource("policyChanges")
    void policyInputsRequireFullModification(String field, Object value) throws Exception {
        assertThat(classifier.classifyPersonal(current("r1"), replace(request(), field, value)))
                .isEqualTo(FULL);
    }

    /** 실제 정책 입력과 기념품 선택 변경 후보를 제공한다. */
    static Stream<Arguments> policyChanges() {
        return Stream.of(
                Arguments.of("birth", "1990-01-02"),
                Arguments.of("eventCategoryId", "c2"),
                Arguments.of("guardianName", "다른 보호자"),
                Arguments.of("guardianConsent", false),
                Arguments.of("guardianConsent", null),
                Arguments.of("selectedSouvenirList", List.of(new SouvenirJson("s3", "M"))),
                Arguments.of("selectedSouvenirList", List.of(new SouvenirJson("s1", "L"), new SouvenirJson("s2", "FREE"))),
                Arguments.of("selectedSouvenirList", List.of(new SouvenirJson("s1", "M"))),
                Arguments.of("selectedSouvenirList", List.of(new SouvenirJson("s1", "M"), new SouvenirJson("s2", null))));
    }

    /** 동일 요청과 인증 입력의 차이는 업무 변경이 아니다. 인증 성공을 의미하지는 않는다. */
    @Test
    void unchangedAndAuthenticationOnly() throws Exception {
        assertThat(classifier.classifyPersonal(current("r1"), request())).isEqualTo(NONE);
        RegistrationAccessRequest otherAccess = new RegistrationAccessRequest("다른 인증", "2000-01-01", "다른 번호", "test-only");
        assertThat(classifier.classifyPersonal(current("r1"), replace(request(), "access", otherAccess))).isEqualTo(NONE);
    }

    /** 이름과 전화번호의 동시 정정에서도 정책 입력과 저장 엔티티를 보존한다. */
    @Test
    void bothIdentityFieldsAndNoEntityMutation() throws Exception {
        Registration current = current("r1");
        Registration before = current("r1");
        RegistrationModificationRequest changed = replace(replace(request(), "name", "새 이름"), "phNum", "새 번호");
        assertThat(classifier.classifyPersonal(current, changed)).isEqualTo(PERSONAL_INFORMATION);
        assertThat(current).usingRecursiveComparison().isEqualTo(before);
    }

    /** 기념품 순서와 기존 trim·strip 정규화만 다른 요청은 변경 없음이다. */
    @Test
    void orderAndExistingNormalization() throws Exception {
        RegistrationModificationRequest changed = replace(request(), "selectedSouvenirList",
                List.of(new SouvenirJson("s2", "FREE"), new SouvenirJson("s1", " M ")));
        changed = replace(changed, "guardianName", " 보호자 ");
        assertThat(classifier.classifyPersonal(current("r1"), changed)).isEqualTo(NONE);
    }

    /** 이름에는 새로운 공백 제거 정책을 적용하지 않는다. */
    @Test
    void nameWhitespaceIsAnActualChange() throws Exception {
        assertThat(classifier.classifyPersonal(current("r1"), replace(request(), "name", " 이름 ")))
                .isEqualTo(PERSONAL_INFORMATION);
    }

    /** 보호자 미지정의 기존 저장 의미인 null과 false를 그대로 따른다. */
    @Test
    void omittedGuardianValuesFollowExistingApplication() throws Exception {
        Registration current = Registration.builder().id("r1").eventCategory(EventCategory.builder().id("c1").build())
                .souvenirJson(souvenirs()).name("이름").phNum("010-1111-2222").birth("1990-01-01")
                .gender(GenderClass.M).address("주소").addressDetail("상세주소").guardianConsent(false).build();
        RegistrationModificationRequest changed = replace(replace(request(), "guardianName", "  "), "guardianConsent", null);
        assertThat(classifier.classifyPersonal(current, changed)).isEqualTo(NONE);
    }

    /** 동일 ID의 서로 다른 사이즈도 중복 선택으로 거부한다. */
    @Test
    void duplicateSouvenirsAreNotCollapsed() throws Exception {
        RegistrationModificationRequest changed = replace(request(), "selectedSouvenirList",
                List.of(new SouvenirJson("s1", "M"), new SouvenirJson("s1", "L")));
        assertThatThrownBy(() -> classifier.classifyPersonal(current("r1"), changed))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_SOUVENIR_SELECTION));
    }

    /** 단체의 순서 변경은 무시하고 일부 개인정보 변경은 요청 전체의 개인정보 수정으로 분류한다. */
    @Test
    void organizationOrderAndPartialPersonalChange() throws Exception {
        List<Registration> members = List.of(current("r1"), current("r2"));
        assertThat(classifier.classifyOrganization(members, organization(member("r2"), member("r1")))).isEqualTo(NONE);
        OrgRegistrationModificationParticipantRequest changed = replace(member("r2"), "gender", GenderClass.F);
        assertThat(classifier.classifyOrganization(members, organization(changed, member("r1"))))
                .isEqualTo(PERSONAL_INFORMATION);
    }

    /** 단체 구성원의 모든 수정 업무 필드도 개인과 동일한 영향 기준으로 분류한다. */
    @ParameterizedTest
    @MethodSource("organizationChanges")
    void organizationFieldClassification(String field, Object value,
                                         RegistrationModificationClassifier.Change expected) throws Exception {
        OrgRegistrationModificationParticipantRequest changed = replace(member("r1"), field, value);
        assertThat(classifier.classifyOrganization(List.of(current("r1")), organization(changed)))
                .isEqualTo(expected);
    }

    /** 단체 DTO에 실제 존재하는 개인정보와 영향 입력의 변경을 제공한다. */
    static Stream<Arguments> organizationChanges() {
        return Stream.of(
                Arguments.of("name", "정정 이름", PERSONAL_INFORMATION),
                Arguments.of("phNum", "010-2222-3333", PERSONAL_INFORMATION),
                Arguments.of("gender", GenderClass.F, PERSONAL_INFORMATION),
                Arguments.of("birth", "1990-01-02", FULL),
                Arguments.of("eventCategoryId", "c2", FULL),
                Arguments.of("selectedSouvenirList", List.of(new SouvenirJson("s3", "M")), FULL),
                Arguments.of("selectedSouvenirList", List.of(new SouvenirJson("s1", "L"), new SouvenirJson("s2", "FREE")), FULL));
    }

    /** 구성원 집합 변경 또는 한 명의 정책 입력 변경은 전체 경로가 담당한다. */
    @Test
    void membershipAndMixedPolicyChanges() throws Exception {
        List<Registration> members = List.of(current("r1"), current("r2"));
        assertThat(classifier.classifyOrganization(members, organization(member("r1")))).isEqualTo(FULL);
        assertThat(classifier.classifyOrganization(members, organization(member("r1"), member("r2"), member(null))))
                .isEqualTo(FULL);
        assertThat(classifier.classifyOrganization(members, organization(member("r1"), member(null)))).isEqualTo(FULL);
        OrgRegistrationModificationParticipantRequest changed = replace(member("r2"), "birth", "1990-01-02");
        OrgRegistrationModificationParticipantRequest personal = replace(member("r1"), "name", "정정 이름");
        assertThat(classifier.classifyOrganization(members, organization(personal, changed))).isEqualTo(FULL);
    }

    /** 중복 ID와 접근 범위 밖 ID는 전체 수정 판정으로 숨기지 않고 기존 오류로 거부한다. */
    @Test
    void invalidOrganizationTargets() {
        List<Registration> members = List.of(current("r1"), current("r2"));
        assertThatThrownBy(() -> classifier.classifyOrganization(members, organization(member("r1"), member("r1"))))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_REGISTRATION_MODIFICATION_TARGET));
        assertThatThrownBy(() -> classifier.classifyOrganization(members, organization(member("r1"), member("outside"))))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET));
    }

    /** null·빈 목록은 생략 패치로 해석하지 않으며 기존 필수 입력 계약을 유지한다. */
    @Test
    void invalidSelectionInput() throws Exception {
        RegistrationModificationRequest missing = replace(request(), "selectedSouvenirList", null);
        RegistrationModificationRequest empty = replace(request(), "selectedSouvenirList", List.of());
        assertThatThrownBy(() -> classifier.classifyPersonal(current("r1"), missing)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> classifier.classifyPersonal(current("r1"), empty)).isInstanceOf(CustomException.class);
    }

    /** 새 DTO 업무 필드가 생기면 비교 책임과 정책 분류 검토 없이 넘어가지 않도록 실패시킨다. */
    @Test
    void requestFieldInventoryRequiresExplicitReview() {
        assertThat(fieldNames(RegistrationModificationRequest.class)).containsExactlyInAnyOrder(
                "access", "eventCategoryId", "selectedSouvenirList", "name", "phNum", "birth", "gender",
                "address", "addressDetail", "guardianName", "guardianConsent");
        assertThat(fieldNames(OrgRegistrationModificationParticipantRequest.class)).containsExactlyInAnyOrder(
                "registrationId", "eventCategoryId", "selectedSouvenirList", "name", "phNum", "birth", "gender");
        assertThat(fieldNames(OrgRegistrationModificationRequest.class)).containsExactlyInAnyOrder("access", "registrations");
        assertThat(fieldNames(SouvenirJson.class)).containsExactlyInAnyOrder("souvenirId", "selectedSize");
    }

    /** 비교용 엔티티만 구성한다. 정책·이벤트·예약·결제 객체나 Spring 컨텍스트가 필요하지 않다. */
    private Registration current(String id) {
        return Registration.builder().id(id).eventCategory(EventCategory.builder().id("c1").build())
                .souvenirJson(souvenirs()).name("이름").phNum("010-1111-2222").birth("1990-01-01")
                .gender(GenderClass.M).address("주소").addressDetail("상세주소")
                .guardianName("보호자").guardianConsent(true).version(3L)
                .contractAmount(new BigDecimal("10000")).paidAmount(new BigDecimal("5000"))
                .status(RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED).build();
    }

    /** 현재 저장값과 일치하는 개인 요청을 만든다. 인증은 판정 외부의 책임이다. */
    private RegistrationModificationRequest request() {
        return new RegistrationModificationRequest(null, "c1", souvenirs(), "이름", "010-1111-2222",
                "1990-01-01", GenderClass.M, "주소", "상세주소", "보호자", true);
    }

    /** 순서와 사이즈 비교에 사용할 저장 기념품을 구성한다. */
    private List<SouvenirJson> souvenirs() {
        return List.of(new SouvenirJson("s1", "M"), new SouvenirJson("s2", "FREE"));
    }

    /** 단체의 기존 또는 신규 구성원 요청을 구성한다. uniqueInfo 검증은 별도 책임이다. */
    private OrgRegistrationModificationParticipantRequest member(String id) {
        return new OrgRegistrationModificationParticipantRequest(id, "c1", souvenirs(), "이름", "010-1111-2222",
                "1990-01-01", GenderClass.M);
    }

    /** 최종 구성원 목록을 단체 요청으로 감싼다. */
    private OrgRegistrationModificationRequest organization(OrgRegistrationModificationParticipantRequest... members) {
        return new OrgRegistrationModificationRequest(null, List.of(members));
    }

    /** DTO 필드 추가를 감지하기 위한 이름 목록을 추출한다. */
    private List<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    /** 테스트에서 한 필드만 바꾸어 나머지 요청값이 실수로 달라지는 것을 방지한다. */
    private <T extends Record> T replace(T source, String field, Object value) throws Exception {
        RecordComponent[] components = source.getClass().getRecordComponents();
        Class<?>[] types = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
        Object[] values = new Object[components.length];
        boolean found = false;
        for (int index = 0; index < components.length; index++) {
            boolean selected = components[index].getName().equals(field);
            found |= selected;
            values[index] = selected ? value : components[index].getAccessor().invoke(source);
        }
        assertThat(found).as("실제 요청 필드: %s", field).isTrue();
        @SuppressWarnings("unchecked")
        T result = (T) source.getClass().getDeclaredConstructor(types).newInstance(values);
        return result;
    }
}
