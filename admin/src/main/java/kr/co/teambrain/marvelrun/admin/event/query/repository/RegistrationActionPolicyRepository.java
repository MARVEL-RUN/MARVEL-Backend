package kr.co.teambrain.marvelrun.admin.event.query.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationActionPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 대회별 활성 정책을 JPA 파생 조회로 읽는다. 조회 실패를 정책 없음으로 숨기지 않는다. */
public interface RegistrationActionPolicyRepository extends JpaRepository<RegistrationActionPolicy, String> {

    /** 같은 요청에서 재사용할 활성 정책을 작업·신청일 시작·식별자 순으로 읽는다. */
    @Transactional(readOnly = true)
    List<RegistrationActionPolicy> findAllByEventIdAndEnabledTrueOrderByActionTypeAscRegistrationStartAtAscIdAsc(String eventId);
}