package kr.co.teambrain.marvelrun.admin.user.query.util;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.user.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.admin.user.query.dto.OrganizationSearchCondition;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class OrganizationSpecification {

    public static Specification<Organization> searchWith(OrganizationSearchCondition condition) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            // 현재 행의 실제 필터 연결로 검색하며 단체 연결을 개인에게 대신 적용하지 않는다.
            if (StringUtils.hasText(condition.referenceFilterId())) {
                predicates.add(cb.equal(root.get("referenceFilterId"), condition.referenceFilterId()));
            }
            // 일반 단체 목록은 활성 계정만 제공하며 금융 이력 조회는 별도 유지한다.
            predicates.add(cb.isFalse(root.get("softDeleted")));

            // 1. 대회(Event) 필터 추가
            if (StringUtils.hasText(condition.eventId())) {
                Join<Organization, Event> eventJoin = root.join("event", JoinType.INNER);
                predicates.add(cb.equal(eventJoin.get("id"), condition.eventId()));
            }

            // 2. 통합 검색어(Keyword) 필터 (단체명, 단체대표자명)
            if (StringUtils.hasText(condition.keyword())) {
                String likeKeyword = "%" + condition.keyword() + "%";

                Predicate groupNameMatch = cb.like(root.get("groupName"), likeKeyword);
                Predicate leaderNameMatch = cb.like(root.get("leaderName"), likeKeyword);

                predicates.add(cb.or(groupNameMatch, leaderNameMatch));
            }

            // N+1 방지를 위한 fetch join (Count 쿼리일 때는 제외)
            if (Long.class != query.getResultType()) {
                root.fetch("event", JoinType.INNER);
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}