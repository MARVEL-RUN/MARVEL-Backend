package kr.co.teambrain.marvelrun.user.event.query.service;

import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PaymentCancel;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Souvenir;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationAccessVerifier;
import kr.co.teambrain.marvelrun.user.event.query.dto.RegistrationReceiptResponse;
import kr.co.teambrain.marvelrun.user.event.query.repository.RegistrationReceiptQueryRepository;
import kr.co.teambrain.marvelrun.user.event.query.support.RegistrationReceiptPaymentResolver;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentCancelAllocation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 본인확인 후 접수 확인 화면용 정보만 구성하며 금융 데이터는 내부 판단에만 사용한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RegistrationReceiptQueryService {
    private final RegistrationReceiptQueryRepository repository;
    private final RegistrationReceiptPaymentResolver paymentResolver;
    private final ServerTimeProvider time;

    /** 개인 신청 ID 없이 현재 본인정보로 조회하며 취소·재신청 이력은 별도 카드로 반환한다. */
    public List<RegistrationReceiptResponse> personal(String eventId, RegistrationAccessRequest access) {
        List<RegistrationReceiptResponse> result = new ArrayList<>();
        for (Registration registration : repository.findPersonalCandidates(
                eventId, access.name(), access.birth(), access.phNum())) {
            try {
                RegistrationAccessVerifier.verifyPersonal(registration, access);
            } catch (CustomException exception) {
                if (exception.getErrorCode() == ErrorCode.REGISTRATION_ACCESS_DENIED) {
                    continue;
                }
                throw exception;
            }
            result.add(receipt(registration.getEvent(), null, List.of(registration),
                    repository.findPersonalPayments(registration.getId())));
        }
        if (result.isEmpty()) {
            throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED);
        }
        return List.copyOf(result);
    }

    /** 단체 로그인 정보로 서버의 전체 명단을 조회하고 결제 정보를 단체 단위로 한 번 표시한다. */
    public List<RegistrationReceiptResponse> organization(String eventId, OrganizationAccessRequest access) {
        List<RegistrationReceiptResponse> result = new ArrayList<>();
        for (Organization organization : repository.findOrganizationCandidates(eventId, access.loginId())) {
            try {
                RegistrationAccessVerifier.verifyOrganization(organization, access);
            } catch (CustomException exception) {
                if (exception.getErrorCode() == ErrorCode.ORGANIZATION_ACCESS_DENIED) {
                    continue;
                }
                throw exception;
            }
            result.add(receipt(organization.getEvent(), organization,
                    repository.findOrganizationMembers(eventId, organization.getId()),
                    repository.findOrganizationPayments(organization.getId())));
        }
        if (result.isEmpty()) {
            throw new CustomException(ErrorCode.ORGANIZATION_ACCESS_DENIED);
        }
        return List.copyOf(result);
    }

    /** 현재 데이터의 동일 읽기 스냅샷에서 표시 금액·기념품·결제 동작을 구성한다. */
    private RegistrationReceiptResponse receipt(Event event, Organization organization,
            List<Registration> registrations, List<Payment> payments) {
        List<String> registrationIds = registrations.stream().map(Registration::getId).toList();
        Map<String, Reservation> reservations = new HashMap<>();
        for (Reservation reservation : repository.findReservations(registrationIds)) {
            reservations.put(reservation.getRegistration().getId(), reservation);
        }
        List<String> paymentIds = payments.stream().map(Payment::getId).toList();
        List<PaymentAllocation> allocations = repository.findAllocations(paymentIds);
        List<PaymentCancel> refunds = repository.findRefunds(paymentIds);
        List<PaymentCancelAllocation> refundAllocations = repository.findRefundAllocations(paymentIds);
        RegistrationReceiptPaymentResolver.View view = paymentResolver.resolve(
                event, registrations, payments, allocations, refunds, refundAllocations,
                reservations, time.currentDateTime());
        List<String> souvenirIds = registrations.stream()
                .flatMap(r -> selections(r).stream()).map(SouvenirJson::souvenirId).distinct().toList();
        Map<String, String> names = new HashMap<>();
        for (Souvenir souvenir : repository.findSouvenirs(event.getId(), souvenirIds)) {
            names.put(souvenir.getId(), souvenir.getName());
        }
        List<RegistrationReceiptResponse.Member> members = new ArrayList<>();
        Map<ItemKey, Integer> totalItems = new LinkedHashMap<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal paidAmount = BigDecimal.ZERO;
        for (Registration registration : registrations) {
            Map<ItemKey, Integer> individual = new LinkedHashMap<>();
            for (SouvenirJson selection : selections(registration)) {
                // 현 모델은 동일 신청·기념품·사이즈당 한 개이며 별도 수량 컬럼이 없다.
                individual.put(new ItemKey(selection.souvenirId(), selection.selectedSize()), 1);
            }
            if (!registration.isSoftDeleted()) {
                individual.forEach((key, count) -> totalItems.merge(key, count, Integer::sum));
            }
            members.add(new RegistrationReceiptResponse.Member(registration.getId(), registration.getName(),
                    registration.getEventCategory().getName(), items(individual, names),
                    registration.isSoftDeleted(), registration.getStatus().name()));
            if (registration.getContractAmount() != null) {
                totalAmount = totalAmount.add(registration.getContractAmount());
            }
            if (registration.getPaidAmount() != null) {
                paidAmount = paidAmount.add(registration.getPaidAmount());
            }
        }
        // null 금액을 0인 정상 값처럼 표시하지 않는다.
        if (registrations.stream().anyMatch(r -> r.getContractAmount() == null)) {
            totalAmount = null;
        }
        if (registrations.stream().anyMatch(r -> r.getPaidAmount() == null)) {
            paidAmount = null;
        }
        return new RegistrationReceiptResponse(
                organization == null ? registrations.get(0).getId() : null,
                organization == null ? null : organization.getId(),
                organization == null ? null : organization.getGroupName(),
                List.copyOf(members), items(totalItems, names), totalAmount, paidAmount,
                view.status(), view.label(), view.warning(), view.action(), view.paymentId(), view.orderId());
    }

    /** 저장된 선택 목록을 반환하며 새 기념품이나 수량을 임의로 추가하지 않는다. */
    private List<SouvenirJson> selections(Registration registration) {
        return registration.getSouvenirJson() == null ? List.of() : registration.getSouvenirJson();
    }

    /** 같은 ID·사이즈의 선택을 수량으로 표시하며 삭제된 상품의 이름을 추측하지 않는다. */
    private List<RegistrationReceiptResponse.SouvenirItem> items(
            Map<ItemKey, Integer> counts, Map<String, String> names) {
        List<RegistrationReceiptResponse.SouvenirItem> result = new ArrayList<>();
        counts.forEach((key, count) -> result.add(new RegistrationReceiptResponse.SouvenirItem(
                key.id(), names.getOrDefault(key.id(), "기념품 정보 없음"), key.size(), count)));
        return List.copyOf(result);
    }

    /** 동명이품과 다른 사이즈가 함께 합쳐지지 않도록 구분하는 집계 키이다. */
    private record ItemKey(String id, String size) { }
}
