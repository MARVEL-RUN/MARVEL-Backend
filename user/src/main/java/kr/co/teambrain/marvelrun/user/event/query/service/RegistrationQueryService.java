package kr.co.teambrain.marvelrun.user.event.query.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GuardianBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationAccessVerifier;
import kr.co.teambrain.marvelrun.user.event.query.dto.*;
import kr.co.teambrain.marvelrun.user.event.query.repository.RegistrationQueryData;
import kr.co.teambrain.marvelrun.user.event.query.repository.RegistrationQueryRepository;
import kr.co.teambrain.marvelrun.user.event.query.support.RegistrationPaymentQueryResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** 개인·단체 응답은 분리하고 인증·선택명·결제 안내의 일괄 조회만 공유한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RegistrationQueryService {
    private final RegistrationQueryRepository repository;
    private final RegistrationPaymentQueryResolver paymentResolver;
    private final ServerTimeProvider time;

    /** 개인 취소·재신청 이력도 일괄 조회하고 현재 본인확인을 통과한 행만 반환한다. */
    public List<RegistrationQueryResponse> personal(String eventId, RegistrationAccessRequest access) {
        LocalDateTime now = time.currentDateTime();
        List<RegistrationQueryData.Member> members = new ArrayList<>();
        for (RegistrationQueryData.Member row : repository.personal(eventId, access)) {
            try {
                RegistrationAccessVerifier.verifyPersonal(row.name(), row.birth(), row.phNum(), row.password(), access);
                members.add(row);
            } catch (CustomException exception) {
                if (exception.getErrorCode() != ErrorCode.REGISTRATION_ACCESS_DENIED) { throw exception; }
            }
        }
        if (members.isEmpty()) { throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED); }
        Map<String, String> names = souvenirNames(eventId, members);
        List<RegistrationQueryData.Payment> payments = repository.personalPayments(eventId,
                members.stream().map(RegistrationQueryData.Member::id).toList());
        List<String> ids = payments.stream().map(RegistrationQueryData.Payment::id).toList();
        Map<String, List<RegistrationQueryData.Allocation>> byPayment = repository.allocations(ids).stream()
                .collect(Collectors.groupingBy(RegistrationQueryData.Allocation::paymentId));
        // 전체 환불의 최신순을 유지하여 과거 주문의 최근 환불도 올바르게 표시한다.
        List<RegistrationQueryData.Refund> refunds = repository.refunds(ids);
        Map<String, List<RegistrationQueryData.Payment>> byRegistration = payments.stream()
                .collect(Collectors.groupingBy(RegistrationQueryData.Payment::registrationId));
        List<RegistrationQueryResponse> result = new ArrayList<>();
        for (RegistrationQueryData.Member row : members) {
            List<RegistrationQueryData.Payment> own = byRegistration.getOrDefault(row.id(), List.of());
            Set<String> ownIds = own.stream().map(RegistrationQueryData.Payment::id).collect(Collectors.toSet());
            List<RegistrationQueryData.Allocation> shares = own.stream()
                    .flatMap(p -> byPayment.getOrDefault(p.id(), List.of()).stream()).toList();
            RegistrationPaymentQueryResolver.Result payment = paymentResolver.resolve(List.of(row), own, shares,
                    refunds.stream().filter(r -> ownIds.contains(r.paymentId())).toList(), row.paymentDeadline(), now);
            result.add(new RegistrationQueryResponse(row.id(), row.name(), row.birth(), row.phNum(), row.gender(),
                    row.categoryId(), row.categoryName(), selections(row, names), row.address(), row.addressDetail(),
                    row.guardianName(), row.guardianPhNum(), row.status(), row.contractAmount(), row.paidAmount(),
                    payment.status(), payment.refundStatus(), payment.action(), payment.warning(), payment.paymentId(), payment.orderId()));
        }
        return List.copyOf(result);
    }

    /** 활성 단체원만 반환하며 금액도 조회 당시 활성 명단 기준으로 합산한다. */
    public List<OrgRegistrationQueryResponse> organization(String eventId, OrganizationAccessRequest access) {
        LocalDateTime now = time.currentDateTime();
        List<OrgRegistrationQueryResponse> result = new ArrayList<>();
        for (RegistrationQueryData.Organization org : repository.organizations(eventId, access.loginId())) {
            try {
                RegistrationAccessVerifier.verifyOrganization(org.loginId(), org.password(), access);
            } catch (CustomException exception) {
                if (exception.getErrorCode() == ErrorCode.ORGANIZATION_ACCESS_DENIED) { continue; }
                throw exception;
            }
            List<RegistrationQueryData.Member> members = repository.members(eventId, org.id());
            Map<String, String> names = souvenirNames(eventId, members);
            List<RegistrationQueryData.Payment> payments = repository.payments(eventId, org.id(), true);
            List<String> ids = payments.stream().map(RegistrationQueryData.Payment::id).toList();
            RegistrationPaymentQueryResolver.Result payment = paymentResolver.resolve(members, payments,
                    repository.allocations(ids), repository.refunds(ids), org.paymentDeadline(), now);
            List<OrgRegistrationParticipantResponse> participants = members.stream().map(row ->
                    new OrgRegistrationParticipantResponse(row.id(), row.name(), row.birth(), row.phNum(), row.gender(),
                            row.categoryId(), row.categoryName(), selections(row, names),
                            row.address() == null ? org.address() : row.address(),
                            row.address() == null ? org.addressDetail() : row.addressDetail(),
                            row.guardianBase() == GuardianBase.ORG_LEADER ? org.leaderName() : row.guardianName(),
                            row.guardianBase() == GuardianBase.ORG_LEADER ? org.phNum() : row.guardianPhNum(), row.status())).toList();
            BigDecimal total = members.stream().map(RegistrationQueryData.Member::contractAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal paid = members.stream().map(RegistrationQueryData.Member::paidAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            result.add(new OrgRegistrationQueryResponse(org.id(), org.name(), org.loginId(), org.leaderName(),
                    org.birth(), org.phNum(), org.email(), org.address(), org.addressDetail(), participants, total, paid,
                    groupStatus(members), payment.status(), payment.refundStatus(), payment.action(), payment.warning(),
                    payment.paymentId(), payment.orderId()));
        }
        if (result.isEmpty()) { throw new CustomException(ErrorCode.ORGANIZATION_ACCESS_DENIED); }
        return List.copyOf(result);
    }

    /** 구성원 수와 관계없이 필요한 기념품 이름을 한 번에 읽는다. */
    private Map<String, String> souvenirNames(String eventId, List<RegistrationQueryData.Member> members) {
        List<String> ids = members.stream().flatMap(r -> r.souvenirs().stream()).map(SouvenirJson::souvenirId).distinct().toList();
        Map<String, String> result = new HashMap<>();
        for (RegistrationQueryData.Souvenir souvenir : repository.souvenirs(eventId, ids)) { result.put(souvenir.id(), souvenir.name()); }
        return result;
    }

    /** 현재 모델의 한 참가자당 선택 한 개와 수정에 필요한 ID·사이즈를 함께 반환한다. */
    private List<RegistrationSouvenirResponse> selections(RegistrationQueryData.Member row, Map<String, String> names) {
        return row.souvenirs().stream().map(s -> new RegistrationSouvenirResponse(
                s.souvenirId(), names.getOrDefault(s.souvenirId(), "기념품 정보 없음"), s.selectedSize(), 1)).toList();
    }

    /** 단체 대표 상태만 기존 enum으로 요약하며 참가자별 원래 상태도 응답에 유지한다. */
    private RegistrationStatus groupStatus(List<RegistrationQueryData.Member> members) {
        if (members.isEmpty()) { return RegistrationStatus.CANCELED; }
        Set<RegistrationStatus> statuses = members.stream().map(RegistrationQueryData.Member::status).collect(Collectors.toSet());
        for (RegistrationStatus status : List.of(RegistrationStatus.PAYMENT_PENDING,
                RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED, RegistrationStatus.PARTIAL_REFUND_REQUIRED,
                RegistrationStatus.CANCELLATION_PENDING, RegistrationStatus.PENDING, RegistrationStatus.EXPIRED)) {
            if (statuses.contains(status)) { return status; }
        }
        return members.get(0).status();
    }
}
