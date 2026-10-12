package kr.co.teambrain.marvelrun.admin.event.query.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationActionPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Collection;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationActionType;

/** 대회별 활성 정책을 JPA 파생 조회로 읽는다. 조회 실패를 정책 없음으로 숨기지 않는다. */
public interface RegistrationActionPolicyRepository extends JpaRepository<RegistrationActionPolicy, String> {

    /** 같은 요청에서 재사용할 활성 정책을 작업·신청일 시작·식별자 순으로 읽는다. */
    @Transactional(readOnly = true)
    List<RegistrationActionPolicy> findAllByEventIdAndActionTypeInAndEnabledTrueOrderByActionTypeAscRegistrationStartAtAscIdAsc(
            String eventId, Collection<RegistrationActionType> actionTypes);

    /** 단일 작업은 해당 enum 조건으로만 활성 정책을 조회한다. */
    @Transactional(readOnly = true)
    List<RegistrationActionPolicy> findAllByEventIdAndActionTypeAndEnabledTrueOrderByRegistrationStartAtAscIdAsc(
            String eventId, RegistrationActionType actionType);
}
