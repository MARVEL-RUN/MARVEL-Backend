package kr.co.teambrain.marvelrun.admin.event.command.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationActionPolicy;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationActionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** 차수 변경이 지우고 다시 넣을 정책 행을 활성 여부와 관계없이 읽는다. */
public interface RegistrationActionPolicyCommandRepository extends JpaRepository<RegistrationActionPolicy, String> {

    /** 대회의 해당 작업 정책을 모두 읽는다(비활성 포함). */
    List<RegistrationActionPolicy> findAllByEventIdAndActionTypeIn(String eventId, Collection<RegistrationActionType> actionTypes);
}
