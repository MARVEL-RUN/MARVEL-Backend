package kr.co.teambrain.marvelrun.admin.user.command.application.service;

import jakarta.persistence.EntityManager;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.admin.user.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.repository.RegistrationCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 기존 대회·단체 잠금을 가진 취소/환불 트랜잭션에서 단체 종료를 판정한다. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class OrganizationLifecycleService {
    private final EntityManager entityManager;
    private final RegistrationCommandRepository registrations;

    /** 모든 참가자 취소와 순납부액 정리 및 진행 중 환불 부재를 확인한 뒤 삭제 표시한다. */
    public void completeOrganizationCancellation(String organizationId) {
        if (organizationId == null) {
            return;
        }
        Organization organization = entityManager.find(Organization.class, organizationId);
        if (organization == null || organization.isSoftDeleted()) {
            return;
        }
        List<Registration> members = registrations.findAllByOrganization_Id(organizationId);
        if (members.isEmpty() || members.stream().anyMatch(member -> !member.isSoftDeleted()
                || (member.getStatus() != RegistrationStatus.CANCELED && member.getStatus() != RegistrationStatus.EXPIRED) || member.getPaidAmount() == null
                || member.getPaidAmount().signum() != 0)) {
            return;
        }

        // 직접 단체 결제와 신청/귀속을 통한 결제를 모두 포함한다.
        Long pending = entityManager.createQuery("""
                select count(c) from PaymentCancel c
                where c.status in :pending and (
                  c.payment.id in (select p.id from Payment p where p.organization.id = :organizationId)
                  or c.payment.id in (select p.id from Payment p where p.registration.organization.id = :organizationId)
                  or c.payment.id in (select a.payment.id from PaymentAllocation a where a.registration.organization.id = :organizationId)
                )
                """, Long.class)
                .setParameter("organizationId", organizationId)
                .setParameter("pending", List.of(PaymentCancelStatus.PROCESSING, PaymentCancelStatus.UNKNOWN))
                .getSingleResult();
        if (pending == 0) {
            organization.markOrganizationSoftDeleted();
        }
    }
}