package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationModificationCandidateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationModificationCandidateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationModificationParticipantRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 실제 Pricing 계산점을 사용하여 수정 전후 계약금액을 검증한다.
 *
 * 후보 정책검증은 Step 8 책임이며,
 * 이 테스트는 검증 완료된 후보를 입력받는 가격 계산 경계를 확인한다.
 */
class RegistrationModificationPricingServiceTest {

    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 9, 22, 12, 0);

    private final RegistrationModificationPricingService service =
            new RegistrationModificationPricingService(
                    new RegistrationPricingService()
            );

    /**
     * 어린이 전환·성인 전환·가격 유지·종목 기본가격 변경을 재계산한다.
     * 이전 계약금액은 계산 후에도 Entity에 그대로 남는다.
     */
    @ParameterizedTest
    @CsvSource({
            "2020-01-01,70000,70000,40000,-30000",
            "1990-01-01,40000,70000,70000,30000",
            "1990-01-01,70000,70000,70000,0",
            "1990-01-01,70000,55000,55000,-15000",
            "2013-11-01,40000,70000,70000,30000",
            "2013-11-02,70000,70000,40000,-30000"
    })
    void recalculatesExistingContractWithoutMutation(
            String candidateBirth,
            String oldAmount,
            String categoryAmount,
            String expectedAmount,
            String expectedDelta
    ) {
        Registration current = existing("r1", oldAmount);

        var candidate = personalCandidate(
                event("test-marvelrun"),
                current,
                category(categoryAmount),
                candidateBirth
        );

        var result = service.repricePersonal(candidate);

        assertThat(result.oldContractAmount())
                .isEqualByComparingTo(oldAmount);
        assertThat(result.newContractAmount())
                .isEqualByComparingTo(expectedAmount);
        assertThat(result.contractDelta())
                .isEqualByComparingTo(expectedDelta);

        assertThat(current.getContractAmount())
                .isEqualByComparingTo(oldAmount);
        assertThat(current.getPaidAmount())
                .isEqualByComparingTo("0");
    }

    /**
     * 대상 대회가 아니면 어린이 후보도 Category 기본가격을 사용한다.
     */
    @Test
    void otherEventDoesNotUseTemporaryChildPrice() {
        var result = service.repricePersonal(
                personalCandidate(
                        event("other-event"),
                        existing("r1", "70000"),
                        category("55000"),
                        "2020-01-01"
                )
        );

        assertThat(result.newContractAmount())
                .isEqualByComparingTo("55000");
        assertThat(result.contractDelta())
                .isEqualByComparingTo("-15000");
    }

    /**
     * 같은 금액의 BigDecimal scale 차이를 실질적인 금액 변경으로 보지 않는다.
     */
    @Test
    void scaleDifferenceProducesZeroDelta() {
        var result = service.repricePersonal(
                personalCandidate(
                        event("test-marvelrun"),
                        existing("r1", "70000.00"),
                        category("70000"),
                        "1990-01-01"
                )
        );

        assertThat(result.contractDelta().signum()).isZero();
    }

    /**
     * 단체 기존·신규 참가자의 결과를 각 후보에 연결하고 요청 순서를 유지한다.
     * 신규 참가자에게 기존 계약의 변경분을 부여하지 않는다.
     */
    @Test
    void pricesExistingAndNewGroupCandidatesSeparately() {
        Event event = event("test-marvelrun");
        EventCategory category = category("70000");

        Registration current = existing("r1", "70000");

        var existingRequest = participant(
                "r1",
                "기존참가자",
                "2020-01-01"
        );

        var newRequest = participant(
                null,
                "신규참가자",
                "1990-01-01"
        );

        var existingCandidate =
                new OrgRegistrationModificationCandidateContext.ParticipantCandidate(
                        current,
                        existingRequest,
                        category,
                        List.of()
                );

        var newCandidate =
                new OrgRegistrationModificationCandidateContext.ParticipantCandidate(
                        null,
                        newRequest,
                        category,
                        List.of()
                );

        var context = new OrgRegistrationModificationCandidateContext(
                event,
                mock(Organization.class),
                List.of(current),
                List.of(existingCandidate, newCandidate),
                new OrgRegistrationModificationRequest(false, 
                        new OrganizationAccessRequest("login", "password"),
                        List.of(existingRequest, newRequest)
                ),
                NOW
        );

        var results = service.repriceOrganization(context);

        assertThat(results).hasSize(2);

        assertThat(results.get(0).candidate())
                .isSameAs(existingCandidate);
        assertThat(results.get(0).price().oldContractAmount())
                .isEqualByComparingTo("70000");
        assertThat(results.get(0).price().newContractAmount())
                .isEqualByComparingTo("40000");
        assertThat(results.get(0).price().contractDelta())
                .isEqualByComparingTo("-30000");

        assertThat(results.get(1).candidate())
                .isSameAs(newCandidate);
        assertThat(results.get(1).price().oldContractAmount())
                .isNull();
        assertThat(results.get(1).price().newContractAmount())
                .isEqualByComparingTo("70000");
        assertThat(results.get(1).price().contractDelta())
                .isNull();

        assertThat(current.getContractAmount())
                .isEqualByComparingTo("70000");
    }

    /**
     * 현재 Pricing 계약이 참조하는 대상 대회와 대회일을 설정한다.
     */
    private Event event(String id) {
        Event event = mock(Event.class);

        when(event.getId()).thenReturn(id);
        when(event.getStartDate())
                .thenReturn(LocalDateTime.of(2026, 11, 1, 9, 0));

        return event;
    }

    /**
     * 후보 종목의 기본 참가비를 설정한다.
     */
    private EventCategory category(String amount) {
        EventCategory category = mock(EventCategory.class);

        when(category.getAmount())
                .thenReturn(new BigDecimal(amount));

        return category;
    }

    /**
     * 이전 계약금액을 가진 실제 Registration 객체를 구성한다.
     */
    private Registration existing(String id, String amount) {
        return Registration.builder()
                .id(id)
                .birth("1990-01-01")
                .contractAmount(new BigDecimal(amount))
                .paidAmount(BigDecimal.ZERO)
                .build();
    }

    /**
     * 인증용 기존 생년월일과 변경 후 후보 생년월일을 분리한다.
     */
    private RegistrationModificationCandidateContext personalCandidate(
            Event event,
            Registration current,
            EventCategory category,
            String birth
    ) {
        RegistrationModificationRequest request =
                new RegistrationModificationRequest(
                new RegistrationAccessRequest(
                                "기존이름",
                                "1990-01-01",
                                "010-1111-2222",
                                "password"
                        ),
                "candidate-category",
                List.of(),
                "후보이름",
                "010-1111-2222",
                birth,
                GenderClass.M,
                "주소",
                "상세",
                true,
                "보호자",
                null,
                null
        );

        return new RegistrationModificationCandidateContext(
                event,
                current,
                category,
                List.of(),
                request,
                NOW
        );
    }

    /**
     * 단체 가격 계산에 사용할 참가자 후보 입력을 구성한다.
     */
    private OrgRegistrationModificationParticipantRequest participant(
            String id,
            String name,
            String birth
    ) {
        return new OrgRegistrationModificationParticipantRequest(
                id,
                "candidate-category",
                List.of(),
                name,
                "010-1111-2222",
                birth,
                GenderClass.M
        );
    }
}