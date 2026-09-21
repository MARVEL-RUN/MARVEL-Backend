package kr.co.teambrain.marvelrun.admin.event.query.util;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import kr.co.teambrain.marvelrun.admin.event.command.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.domain.EventCategory;
import kr.co.teambrain.marvelrun.admin.event.command.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationSearchCondition;
import kr.co.teambrain.marvelrun.common.entity.OrganizationBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 관리자 서버 신청 목록 조회를 위한 동적 쿼리 생성 클래스
 */
public class RegistrationSpecification {

    public static Specification<Registration> searchWith(
            RegistrationSearchCondition condition
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 0. 대회(Event) 필터 추가
            if (StringUtils.hasText(condition.eventId())) {
                Join<Registration, Event> eventJoin = root.join("event", JoinType.INNER);
                predicates.add(cb.equal(eventJoin.get("id"), condition.eventId()));
            }

            // 1. 상태(Status) 필터
            if (condition.status() != null) {
                predicates.add(cb.equal(root.get("status"), condition.status()));
            }

            // 2. 코스(EventCategory) 필터
            if (StringUtils.hasText(condition.eventCategoryId())) {
                Join<Registration, EventCategory> categoryJoin = root.join("eventCategory", JoinType.INNER);
                predicates.add(cb.equal(categoryJoin.get("id"), condition.eventCategoryId()));
            }

            // 3. 유형(개인/단체) 필터
            if (StringUtils.hasText(condition.type())) {
                if ("PERSONAL".equalsIgnoreCase(condition.type())) {
                    predicates.add(cb.isNull(root.get("organization")));
                } else if ("ORGANIZATION".equalsIgnoreCase(condition.type())) {
                    predicates.add(cb.isNotNull(root.get("organization")));
                }
            }

            // 4. 통합 검색어(Keyword) 필터 (암호화가 해제되었으므로 LIKE를 활용한 부분 검색 허용)
            if (StringUtils.hasText(condition.keyword())) {
                Join<Registration, OrganizationBase> orgJoin = root.join("organization", JoinType.LEFT);
                String likeKeyword = "%" + condition.keyword() + "%";

                Predicate nameMatch = cb.like(root.get("name"), likeKeyword);
                Predicate phoneMatch = cb.like(root.get("phNum"), likeKeyword);
                Predicate orgNameMatch = cb.like(orgJoin.get("groupName"), likeKeyword);

                predicates.add(cb.or(nameMatch, phoneMatch, orgNameMatch));
            }

            // N+1 방지를 위한 fetch join (Pageable의 Count 쿼리 실행 시에는 제외)
            if (Long.class != query.getResultType()) {
                root.fetch("eventCategory", JoinType.INNER);
                root.fetch("organization", JoinType.LEFT);
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}