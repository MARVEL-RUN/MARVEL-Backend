package kr.co.teambrain.marvelrun.user.discount.service;

import kr.co.teambrain.marvelrun.common.discount.DiscountGroupSizeEvaluator;
import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 기존 대회·단체 잠금 아래 최종 인원과 확정 인원 보존을 검증한다. */
@Service
@RequiredArgsConstructor
public class OrganizationDiscountGuard {
    private final DiscountPolicyResolver policies;
    private final ReservationCommandRepository reservations;
    private final DiscountGroupSizeEvaluator evaluator = new DiscountGroupSizeEvaluator();

    /** 단체 전체 현재 목록과 최종 유지 ID를 받아 미결제 신규 인원의 대체를 방지한다. */
    public void validateOrganizationMemberChange(Organization organization, List<Registration> current,
            Set<String> retainedIds, int newCount) {
        if (organization.getDiscountLinkId() == null) {
            return;
        }
        String eventId = organization.getEvent().getId();
        DiscountModels.Policy policy = policies.resolveLinkPolicy(policies.resolveStoredLink(organization.getDiscountLinkId()),
                eventId, policies.resolveEventPolicies(eventId));
        List<String> activeIds = current.stream().filter(row -> !row.isSoftDeleted()).map(Registration::getId).toList();
        if (activeIds.isEmpty()) {
            throw new CustomException(ErrorCode.DISCOUNT_CONFIGURATION_ERROR);
        }
        List<Reservation> rows = reservations.findAllByRegistrationIds(activeIds);
        if (rows.size() != activeIds.size()) {
            throw new CustomException(ErrorCode.DISCOUNT_CONFIGURATION_ERROR);
        }
        Set<String> confirmed = rows.stream().filter(row -> row.getStatus() == ReservationStatus.CONSUMED)
                .map(row -> row.getRegistration().getId()).collect(Collectors.toSet());
        int retained = (int) activeIds.stream().filter(retainedIds::contains).count();
        int confirmedAfter = (int) confirmed.stream().filter(retainedIds::contains).count();
        boolean allowed = evaluator.allowsGroupChange(policy.minimumGroupSize(), confirmed.isEmpty(),
                retained + newCount, confirmed.size(), confirmedAfter, retained < activeIds.size(),
                retained == 0 && newCount == 0);
        if (!allowed) {
            throw new CustomException(ErrorCode.DISCOUNT_GROUP_MINIMUM_NOT_MET);
        }
    }
}