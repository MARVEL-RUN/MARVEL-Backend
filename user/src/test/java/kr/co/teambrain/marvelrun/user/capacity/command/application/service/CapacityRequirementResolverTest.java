package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.CapacityType;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityRequirementInput;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityTarget;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * 기존 자원 선택 규칙과 참가자별 조회 재사용을 확인한다.
 */
class CapacityRequirementResolverTest {

    /**
     * 성인·어린이의 공통 정원과 어린이 정원 및 사이즈를 각각 계산한다.
     */
    @Test
    void resolvesAdultAndChildWithSharedLookups() {
        CapacityCommandRepository repository =
                mock(CapacityCommandRepository.class);

        when(repository.findRegistrationTargets(
                "event", "category", CapacityType.EVENT_TOTAL
        )).thenReturn(List.of(
                target("total", CapacityType.EVENT_TOTAL, null, ""),
                target("category-cap", CapacityType.CATEGORY, null, ""),
                target("child-cap", CapacityType.CHILD_CATEGORY, null, ""),
                target("group-cap", CapacityType.CATEGORY_GROUP, null, "")
        ));

        when(repository.findSouvenirTargets(
                "event", Set.of("shirt")
        )).thenReturn(List.of(
                target("shirt-total", CapacityType.SOUVENIR, "shirt", ""),
                target("shirt-m", CapacityType.SOUVENIR, "shirt", "M"),
                target("shirt-130", CapacityType.SOUVENIR, "shirt", "130")
        ));

        CapacityRequirementResolver resolver =
                new CapacityRequirementResolver(repository);

        var results = resolver.resolveAll(
                "event",
                List.of(
                        new CapacityRequirementInput(
                                "category", false,
                                List.of(new SouvenirJson("shirt", "M"))
                        ),
                        new CapacityRequirementInput(
                                "category", true,
                                List.of(new SouvenirJson("shirt", "130"))
                        )
                )
        );

        assertThat(results.get(0))
                .hasSize(5)
                .containsEntry("total", 1)
                .containsEntry("category-cap", 1)
                .containsEntry("group-cap", 1)
                .containsEntry("shirt-total", 1)
                .containsEntry("shirt-m", 1)
                .doesNotContainKeys("child-cap", "shirt-130");

        assertThat(results.get(1))
                .hasSize(6)
                .containsEntry("total", 1)
                .containsEntry("category-cap", 1)
                .containsEntry("child-cap", 1)
                .containsEntry("group-cap", 1)
                .containsEntry("shirt-total", 1)
                .containsEntry("shirt-130", 1)
                .doesNotContainKey("shirt-m");

        verify(repository, times(1)).findRegistrationTargets(
                "event", "category", CapacityType.EVENT_TOTAL
        );
        verify(repository, times(1)).findSouvenirTargets(
                "event", Set.of("shirt")
        );

        verifyNoMoreInteractions(repository);
    }

    /**
     * 사이즈별 설정이 있는데 선택한 사이즈 설정이 없으면 차단한다.
     */
    @Test
    void rejectsMissingSizeSpecificCapacity() {
        CapacityCommandRepository repository =
                mock(CapacityCommandRepository.class);

        when(repository.findRegistrationTargets(
                "event", "category", CapacityType.EVENT_TOTAL
        )).thenReturn(List.of(
                target("total", CapacityType.EVENT_TOTAL, null, ""),
                target("category-cap", CapacityType.CATEGORY, null, "")
        ));

        when(repository.findSouvenirTargets(
                "event", Set.of("shirt")
        )).thenReturn(List.of(
                target("shirt-total", CapacityType.SOUVENIR, "shirt", ""),
                target("shirt-m", CapacityType.SOUVENIR, "shirt", "M")
        ));

        var resolver = new CapacityRequirementResolver(repository);

        assertThatThrownBy(
                () -> resolver.resolveAll(
                        "event",
                        List.of(new CapacityRequirementInput(
                                "category", true,
                                List.of(new SouvenirJson("shirt", "130"))
                        ))
                )
        )
                .isInstanceOf(CustomException.class)
                .satisfies(exception ->
                        assertThat(((CustomException) exception).getErrorCode())
                                .isEqualTo(ErrorCode.CAPACITY_CONFIGURATION_ERROR)
                );
    }

    /**
     * 수정된 생년월일의 13번째 생일 경계로 어린이 정원을 판정한다.
     */
    @Test
    void candidateChildBoundaryUsesEventDate() {
        LocalDate eventDate = LocalDate.of(2026, 11, 1);

        assertThat(CapacityRequirementInput.fromCandidate(
                "category", "2013-11-01", eventDate, List.of()
        ).child()).isFalse();

        assertThat(CapacityRequirementInput.fromCandidate(
                "category", "2013-11-02", eventDate, List.of()
        ).child()).isTrue();
    }

    /**
     * Repository가 반환하는 자원 설정 projection을 구성한다.
     */
    private CapacityTarget target(
            String id,
            CapacityType type,
            String souvenirId,
            String size
    ) {
        return new CapacityTarget(id, type, souvenirId, size);
    }
}