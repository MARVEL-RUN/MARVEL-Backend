package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationPolicyContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.valid.EventPaymentPolicyValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import org.junit.jupiter.api.Tag;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("policy-db")
@DataJpaTest(properties = {
        "spring.datasource.url=${MARVELRUN_TEST_DB_URL}",
        "spring.datasource.username=${MARVELRUN_TEST_DB_USERNAME}",
        "spring.datasource.password=${MARVELRUN_TEST_DB_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=false",
        "spring.liquibase.enabled=false"
})
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@Import({
        RegistrationPolicyLoader.class,
        RegistrationPolicyValidator.class,
        RegistrationApplyValidator.class,
        OrgRegistrationApplyValidator.class,
        EventPaymentPolicyValidator.class
})
class RegistrationPolicyTestDatabaseTest {

    private static final String EVENT_ID = "test-marvelrun";

    /*
     * 테스트 DB의 대회일은 2026-09-10,
     * 신청 기간은 2026-09-10 14:00 ~ 2026-10-09 00:00이다.
     *
     * 시스템 현재 날짜와 관계없이 같은 조건으로 검증한다.
     */
    private static final LocalDateTime APPLICATION_TIME =
            LocalDateTime.of(2026, 9, 20, 12, 0);

    private final ObjectMapper objectMapper =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule());

    @Autowired
    private EventCommandRepository eventCommandRepository;

    @Autowired
    private RegistrationPolicyLoader registrationPolicyLoader;

    @Autowired
    private RegistrationPolicyValidator registrationPolicyValidator;

    @Autowired
    private RegistrationApplyValidator registrationApplyValidator;

    @Autowired
    private OrgRegistrationApplyValidator orgRegistrationApplyValidator;

    @Autowired
    private EventPaymentPolicyValidator eventPaymentPolicyValidator;

    private Event event;

    @BeforeEach
    void loadTestEvent() {
        event = eventCommandRepository.findById(EVENT_ID)
                .orElseThrow(
                        () -> new AssertionError(
                                "test-marvelrun 대회가 없습니다."
                        )
                );

        assertThat(event.getStartDate().toLocalDate())
                .as("이 테스트가 사용하는 대회일")
                .isEqualTo(LocalDate.of(2026, 9, 10));
    }

    @Test
    void policyDataMatchesExpectedBoundaries() {
        RegistrationPolicyContext policies =
                registrationPolicyLoader.load(
                        EVENT_ID,
                        Set.of("category-1", "category-2", "category-3"),
                        Set.of("ecs-14", "ecs-24", "ecs-34")
                );

        assertThat(
                policies.eventPolicy().getGuardianRequiredBirthFrom()
        ).isEqualTo(LocalDate.of(2012, 9, 11));

        assertThat(policies.categoryPolicies()).hasSize(3);

        assertThat(
                policies.categoryPolicies()
                        .get("category-1")
                        .getAllowedBirthTo()
        ).isEqualTo(LocalDate.of(2013, 9, 10));

        assertThat(
                policies.categoryPolicies()
                        .get("category-2")
                        .getAllowedBirthFrom()
        ).isNull();

        assertThat(
                policies.categoryPolicies()
                        .get("category-2")
                        .getAllowedBirthTo()
        ).isNull();

        assertThat(
                policies.categoryPolicies()
                        .get("category-3")
                        .getAllowedBirthFrom()
        ).isNull();

        assertThat(
                policies.categoryPolicies()
                        .get("category-3")
                        .getAllowedBirthTo()
        ).isNull();

        assertThat(policies.souvenirPolicies().get("ecs-14"))
                .isEmpty();

        for (String mappingId : List.of("ecs-24", "ecs-34")) {
            assertThat(policies.souvenirPolicies().get(mappingId))
                    .singleElement()
                    .satisfies(policy -> {
                        assertThat(policy.getBirthFrom())
                                .isEqualTo(LocalDate.of(2013, 9, 11));
                        assertThat(policy.getBirthTo()).isNull();
                        assertThat(policy.getAllowedSizes())
                                .isEqualTo("130|150");
                    });
        }
    }

    @Test
    void applicationTimeBoundaries() {
        LocalDateTime start =
                LocalDateTime.of(2026, 9, 10, 14, 0);
        LocalDateTime deadline =
                LocalDateTime.of(2026, 10, 9, 0, 0);

        expectError(
                ErrorCode.EVENT_REGISTRATION_NOT_STARTED,
                () -> registrationPolicyValidator.validateNewApplication(
                        event,
                        start.minusNanos(1)
                )
        );

        assertThatCode(
                () -> registrationPolicyValidator.validateNewApplication(
                        event,
                        start
                )
        ).doesNotThrowAnyException();

        assertThatCode(
                () -> registrationPolicyValidator.validateNewApplication(
                        event,
                        deadline.minusNanos(1)
                )
        ).doesNotThrowAnyException();

        expectError(
                ErrorCode.EVENT_REGISTRATION_CLOSED,
                () -> registrationPolicyValidator.validateNewApplication(
                        event,
                        deadline
                )
        );
    }

    @Test
    void completeSouvenirSelectionIsAccepted() {
        var context = registrationApplyValidator.validate(
                EVENT_ID,
                personalRequest(
                        "category-2",
                        "2000-01-01",
                        null,
                        false,
                        souvenirs("category-2", "M")
                ),
                APPLICATION_TIME
        );

        assertThat(context.souvenirJsons())
                .extracting(SouvenirJson::souvenirId)
                .containsExactly("souvenir-tshirt");
    }

    /**
     * 유일한 필수 기념품인 티셔츠 선택이 누락되면 신청을 거절한다.
     */
    @Test
    void missingTshirtIsRejected() {
        List<SouvenirJson> selections =
                new ArrayList<>(souvenirs("category-2", "M"));

        selections.removeIf(
                item -> item.souvenirId().equals("souvenir-tshirt")
        );

        expectError(
                ErrorCode.INVALID_EVENT_CATEGORY_SOUVENIR,
                () -> registrationApplyValidator.validate(
                        EVENT_ID,
                        personalRequest(
                                "category-2",
                                "2000-01-01",
                                null,
                                false,
                                selections
                        ),
                        APPLICATION_TIME
                )
        );
    }

    /**
     * 선택 개수가 같더라도 매핑되지 않은 기념품으로 대체하면 거절한다.
     */
    @Test
    void unmappedSouvenirWithSameSelectionCountIsRejected() {
        List<SouvenirJson> selections =
                new ArrayList<>(souvenirs("category-2", "M"));

        selections.set(
                0,
                new SouvenirJson("souvenir-medal-dd", "FREE")
        );

        expectError(
                ErrorCode.INVALID_EVENT_CATEGORY_SOUVENIR,
                () -> registrationApplyValidator.validate(
                        EVENT_ID,
                        personalRequest(
                                "category-2",
                                "2000-01-01",
                                null,
                                false,
                                selections
                        ),
                        APPLICATION_TIME
                )
        );
    }

    @Test
    void duplicateSouvenirIsRejectedBeforeSetComparison() {
        List<SouvenirJson> selections =
                new ArrayList<>(souvenirs("category-2", "M"));

        selections.add(
                new SouvenirJson("souvenir-tshirt", "L")
        );

        expectError(
                ErrorCode.DUPLICATE_SOUVENIR_SELECTION,
                () -> registrationApplyValidator.validate(
                        EVENT_ID,
                        personalRequest(
                                "category-2",
                                "2000-01-01",
                                null,
                                false,
                                selections
                        ),
                        APPLICATION_TIME
                )
        );
    }

    @Test
    void childCannotApplyForTenKm() {
        expectError(
                ErrorCode.REGISTRATION_CATEGORY_BIRTH_NOT_ALLOWED,
                () -> registrationApplyValidator.validate(
                        EVENT_ID,
                        personalRequest(
                                "category-1",
                                "2013-09-11",
                                "보호자",
                                true,
                                souvenirs("category-1", "130")
                        ),
                        APPLICATION_TIME
                )
        );
    }

    @Test
    void thirteenthBirthdayOnEventDateAllowsTenKm() {
        assertThatCode(
                () -> registrationApplyValidator.validate(
                        EVENT_ID,
                        personalRequest(
                                "category-1",
                                "2013-09-10",
                                "보호자",
                                true,
                                souvenirs("category-1", "M")
                        ),
                        APPLICATION_TIME
                )
        ).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({
            "category-2,130",
            "category-2,150",
            "category-3,130",
            "category-3,150"
    })
    void childSizesAreAccepted(
            String categoryId,
            String size
    ) {
        assertThatCode(
                () -> registrationApplyValidator.validate(
                        EVENT_ID,
                        personalRequest(
                                categoryId,
                                "2013-09-11",
                                "보호자",
                                true,
                                souvenirs(categoryId, size)
                        ),
                        APPLICATION_TIME
                )
        ).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({
            "category-2,S",
            "category-2,4XL",
            "category-3,M",
            "category-3,XL"
    })
    void adultSizesAreRejectedForChildren(
            String categoryId,
            String size
    ) {
        expectError(
                ErrorCode.REGISTRATION_SOUVENIR_SIZE_NOT_ALLOWED,
                () -> registrationApplyValidator.validate(
                        EVENT_ID,
                        personalRequest(
                                categoryId,
                                "2013-09-11",
                                "보호자",
                                true,
                                souvenirs(categoryId, size)
                        ),
                        APPLICATION_TIME
                )
        );
    }

    @Test
    void guardianNameIsRequiredUnderFourteenOnEventDate() {
        expectError(
                ErrorCode.GUARDIAN_NAME_REQUIRED,
                () -> registrationApplyValidator.validate(
                        EVENT_ID,
                        personalRequest(
                                "category-2",
                                "2012-09-11",
                                null,
                                true,
                                souvenirs("category-2", "M")
                        ),
                        APPLICATION_TIME
                )
        );
    }

    @Test
    void guardianConsentIsRequiredUnderFourteenOnEventDate() {
        expectError(
                ErrorCode.GUARDIAN_CONSENT_REQUIRED,
                () -> registrationApplyValidator.validate(
                        EVENT_ID,
                        personalRequest(
                                "category-2",
                                "2012-09-11",
                                "보호자",
                                false,
                                souvenirs("category-2", "M")
                        ),
                        APPLICATION_TIME
                )
        );
    }

    @Test
    void fourteenthBirthdayOnEventDateNeedsNoGuardian() {
        assertThatCode(
                () -> registrationApplyValidator.validate(
                        EVENT_ID,
                        personalRequest(
                                "category-2",
                                "2012-09-10",
                                null,
                                false,
                                souvenirs("category-2", "M")
                        ),
                        APPLICATION_TIME
                )
        ).doesNotThrowAnyException();
    }

    @Test
    void futureBirthIsRejectedUsingApplicationDate() {
        expectError(
                ErrorCode.INVALID_REGISTRATION_BIRTH,
                () -> registrationApplyValidator.validate(
                        EVENT_ID,
                        personalRequest(
                                "category-2",
                                "2026-09-21",
                                "보護자",
                                true,
                                souvenirs("category-2", "130")
                        ),
                        APPLICATION_TIME
                )
        );
    }

    @Test
    void leaderMustBeAdultOnEventDateNotApplicationDate() {
        /*
         * 2012-09-11 출생자는 신청 기준시각에는 만 14세지만,
         * 대회일 2026-09-10에는 아직 만 13세이다.
         */
        expectError(
                ErrorCode.ORGANIZATION_LEADER_MUST_BE_ADULT,
                () -> orgRegistrationApplyValidator.validate(
                        EVENT_ID,
                        organizationRequest(
                                "2012-09-11",
                                true,
                                List.of(
                                        participant(
                                                "2013-09-11",
                                                souvenirs("category-2", "130")
                                        )
                                )
                        ),
                        APPLICATION_TIME
                )
        );
    }

    @Test
    void adultLeaderInformationIsUsedForChildParticipant() {
        var context = orgRegistrationApplyValidator.validate(
                EVENT_ID,
                organizationRequest(
                        "2012-09-10",
                        true,
                        List.of(
                                participant(
                                        "2013-09-11",
                                        souvenirs("category-2", "130")
                                )
                        )
                ),
                APPLICATION_TIME
        );

        assertThat(context.registrations()).hasSize(1);
    }

    @Test
    void organizationConsentIsRequiredForChildParticipant() {
        expectError(
                ErrorCode.GUARDIAN_CONSENT_REQUIRED,
                () -> orgRegistrationApplyValidator.validate(
                        EVENT_ID,
                        organizationRequest(
                                "2012-09-10",
                                false,
                                List.of(
                                        participant(
                                                "2013-09-11",
                                                souvenirs("category-2", "130")
                                        )
                                )
                        ),
                        APPLICATION_TIME
                )
        );
    }

    /**
     * 단체원 한 명의 정상 선택이 다른 단체원의 티셔츠 누락을 대신하지 못한다.
     */
    @Test
    void anotherParticipantCannotCoverMissingSouvenir() {
        List<SouvenirJson> incomplete =
                new ArrayList<>(souvenirs("category-2", "130"));

        incomplete.removeIf(
                item -> item.souvenirId().equals("souvenir-tshirt")
        );

        expectError(
                ErrorCode.INVALID_EVENT_CATEGORY_SOUVENIR,
                () -> orgRegistrationApplyValidator.validate(
                        EVENT_ID,
                        organizationRequest(
                                "2012-09-10",
                                true,
                                List.of(
                                        participant(
                                                "2013-09-11",
                                                souvenirs("category-2", "130")
                                        ),
                                        participant(
                                                "2013-09-12",
                                                incomplete
                                        )
                                )
                        ),
                        APPLICATION_TIME
                )
        );
    }

    @Test
    void paymentDeadlineIsIndependentFromRegistrationDeadline() {
        LocalDateTime paymentDeadline =
                LocalDateTime.of(2026, 9, 30, 0, 0);

        assertThatCode(
                () -> eventPaymentPolicyValidator.validateNewPayment(
                        event,
                        paymentDeadline.minusNanos(1)
                )
        ).doesNotThrowAnyException();

        expectError(
                ErrorCode.EVENT_PAYMENT_CLOSED,
                () -> eventPaymentPolicyValidator.validateNewPayment(
                        event,
                        paymentDeadline
                )
        );

        /*
         * 現テストデータでは結제 마감 후에도 신청 기간은 남아 있다.
         * 신청 검증과 결제 검증이 분리되어 있는지 확인한다.
         */
        LocalDateTime afterPaymentDeadline =
                LocalDateTime.of(2026, 10, 1, 12, 0);

        assertThatCode(
                () -> registrationPolicyValidator.validateNewApplication(
                        event,
                        afterPaymentDeadline
                )
        ).doesNotThrowAnyException();

        expectError(
                ErrorCode.EVENT_PAYMENT_CLOSED,
                () -> eventPaymentPolicyValidator.validateNewPayment(
                        event,
                        afterPaymentDeadline
                )
        );
    }

    private RegistrationCreateRequest personalRequest(
            String categoryId,
            String birth,
            String guardianName,
            boolean guardianConsent,
            List<SouvenirJson> selections
    ) {
        Map<String, Object> values =
                new HashMap<>();

        values.put("eventCategoryId", categoryId);
        values.put("selectedSouvenirList", selections);
        values.put("password", "TestPassword1!");
        values.put("name", uniqueName());
        values.put("phNum", "010-0000-0000");
        values.put("birth", birth);
        values.put("gender", GenderClass.values()[0].name());
        values.put("address", "테스트 주소");
        values.put("addressDetail", "테스트 상세주소");
        values.put("guardianName", guardianName);
        values.put("guardianConsent", guardianConsent);

        return objectMapper.convertValue(
                values,
                RegistrationCreateRequest.class
        );
    }

    private Map<String, Object> participant(
            String birth,
            List<SouvenirJson> selections
    ) {
        return Map.of(
                "eventCategoryId", "category-2",
                "selectedSouvenirList", selections,
                "name", uniqueName(),
                "phNum", "010-0000-0000",
                "birth", birth,
                "gender", GenderClass.values()[0].name()
        );
    }

    private OrgRegistrationCreateRequest organizationRequest(
            String leaderBirth,
            boolean guardianConsent,
            List<Map<String, Object>> participants
    ) {
        Map<String, Object> account = Map.of(
                "organizationName", "테스트단체",
                "organizationLoginId", "testgroup",
                "organizationPassword", "TestPassword1!"
        );

        Map<String, Object> profile = Map.of(
                "address", "테스트 주소",
                "addressDetail", "테스트 상세주소",
                "birth", leaderBirth,
                "phNum", "010-0000-0000",
                "email", "test@example.com",
                "leaderName", "테스트단체장",
                "guardianConsent", guardianConsent
        );

        return objectMapper.convertValue(
                Map.of(
                        "account", account,
                        "profile", profile,
                        "registrations", participants
                ),
                OrgRegistrationCreateRequest.class
        );
    }

    /**
     * 현재 신청 대상으로 매핑된 티셔츠 선택값을 생성한다.
     *
     * 모든 종목이 같은 티셔츠를 사용한다.
     * 기존 테스트 호출부 호환을 위해 categoryId 매개변수를 유지한다.
     */
    private List<SouvenirJson> souvenirs(
            String categoryId,
            String shirtSize
    ) {
        return List.of(
                new SouvenirJson("souvenir-tshirt", shirtSize)
        );
    }

    private String uniqueName() {
        return "정책테스트-" + UUID.randomUUID();
    }

    private void expectError(
            ErrorCode expected,
            Runnable action
    ) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        CustomException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(expected)
                );
    }
}