package kr.co.teambrain.marvelrun.admin.event.query.repository;

import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationSearchCondition;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 버추얼 대회 신청 목록·엑셀의 행을 JDBC로 읽는다(읽기 전용).
 * 한 행은 참가 항목 하나다. 구매 줄이 없거나 참가 항목이 없는 신청도 한 행으로 남긴다.
 * 검색 조건은 오프라인 RegistrationSpecification과 같은 뜻으로 맞추되, 참가자 기준으로 거른다.
 */
@Repository
@RequiredArgsConstructor
public class RegistrationRowQueryRepository {

    private static final String SELECT = """
            SELECT r.id AS registration_id, i.id AS item_id, pc.name AS product_name, i.amount AS item_amount,
                   CASE WHEN i.id IS NULL THEN r.souvenir_json ELSE i.souvenir_json END AS souvenir_json,
                   e.id AS entry_id, ec.id AS entry_category_id, ec.name AS entry_category_name,
                   COALESCE(e.name, r.name) AS name, COALESCE(e.birth, r.birth) AS birth,
                   COALESCE(e.gender, r.gender) AS gender, COALESCE(e.ph_num, r.ph_num) AS ph_num,
                   e.bib_number
            """;

    /** 구매 줄(없으면 신청 값), 참가 항목(없을 수 있음), 참가 항목의 종목, 주문자 단체를 잇는다. */
    private static final String FROM = """
            FROM registration r
            LEFT JOIN registration_item i ON i.registration_id = r.id
            JOIN event_category pc ON pc.id = COALESCE(i.event_category_id, r.event_category_id)
            LEFT JOIN registration_entry e ON e.registration_item_id = i.id
            LEFT JOIN event_category ec ON ec.id = e.event_category_id
            LEFT JOIN organization o ON o.id = r.organization_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * 엑셀 전용 범위다. 단체 ID가 있으면 그 단체만, 선택한 신청 ID가 있으면 그 신청만 읽는다.
     * 선택이 없으면 삭제된 신청을 뺀다. 선택한 신청은 삭제 여부와 상관없이 읽는다.
     */
    public record Export(String organizationId, List<String> registrationIds) { }

    /** 한 행 값이다. 이름·생년월일·성별·연락처는 참가 항목 값이고 없으면 신청(주문자) 값이다. */
    public record Row(String registrationId, String itemId, String productName, BigDecimal itemAmount,
            String souvenirJson, String entryId, String entryCategoryId, String entryCategoryName,
            String name, String birth, GenderClass gender, String phNum, String bibNumber) { }

    /** 조건에 맞는 행 수다. 목록의 전체 건수이며 신청 수가 아니다. export가 null이면 목록 조건이다. */
    public long count(RegistrationSearchCondition condition, Export export) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(condition, export, params);
        return jdbc.queryForObject("SELECT COUNT(*) " + FROM + where, params, Long.class);
    }

    /** 신청일시 내림차순, 구매 줄 순서, 종목 순서, 참가 항목 ID 순이다. limit이 null이면 전부 읽는다. */
    public List<Row> find(RegistrationSearchCondition condition, Export export, Integer limit, Long offset) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = SELECT + FROM + where(condition, export, params) + """
                ORDER BY r.registration_date DESC, r.id DESC, i.sort_order, ec.sort_order, e.id
                """;
        if (limit != null) {
            sql += " LIMIT :limit OFFSET :offset";
            params.addValue("limit", limit).addValue("offset", offset);
        }
        return jdbc.query(sql, params, (rs, rowNum) -> new Row(rs.getString("registration_id"),
                rs.getString("item_id"), rs.getString("product_name"), rs.getBigDecimal("item_amount"),
                rs.getString("souvenir_json"), rs.getString("entry_id"), rs.getString("entry_category_id"),
                rs.getString("entry_category_name"), rs.getString("name"), rs.getString("birth"),
                rs.getString("gender") == null ? null : GenderClass.valueOf(rs.getString("gender")),
                rs.getString("ph_num"), rs.getString("bib_number")));
    }

    /** 검색 조건을 WHERE 절로 만든다. 값은 params에 담는다. */
    private String where(RegistrationSearchCondition condition, Export export, MapSqlParameterSource params) {
        List<String> clauses = new ArrayList<>();
        clauses.add("r.event_id = :eventId");
        params.addValue("eventId", condition.eventId());
        if (condition.status() != null) {
            clauses.add("r.status = :status");
            params.addValue("status", condition.status().name());
        }
        if (StringUtils.hasText(condition.type())) {
            if ("PERSONAL".equalsIgnoreCase(condition.type())) {
                clauses.add("r.organization_id IS NULL");
            } else if ("ORGANIZATION".equalsIgnoreCase(condition.type())) {
                clauses.add("r.organization_id IS NOT NULL");
            }
        }
        // 참가자 종목, 구매 상품, 구매 줄이 없을 때의 신청 종목 중 하나라도 맞으면 든다.
        if (StringUtils.hasText(condition.eventCategoryId())) {
            clauses.add("(ec.id = :eventCategoryId OR pc.id = :eventCategoryId)");
            params.addValue("eventCategoryId", condition.eventCategoryId());
        }
        // 주문자와 단체명이 맞으면 그 주문의 모든 행, 받는 사람이 맞으면 그 행이 든다.
        if (StringUtils.hasText(condition.keyword())) {
            clauses.add("(r.name LIKE :keyword OR r.ph_num LIKE :keyword OR o.group_name LIKE :keyword"
                    + " OR e.name LIKE :keyword OR e.ph_num LIKE :keyword)");
            params.addValue("keyword", "%" + condition.keyword() + "%");
        }
        if (StringUtils.hasText(condition.startGroupCode())) {
            clauses.add("r.start_group_id IN (SELECT g.id FROM event_category_start_group g WHERE g.code = :startGroupCode)");
            params.addValue("startGroupCode", condition.startGroupCode());
        }
        if (condition.startGroupUnselected() != null) {
            clauses.add("r.start_group_unselected = :startGroupUnselected");
            params.addValue("startGroupUnselected", condition.startGroupUnselected());
        }
        if (StringUtils.hasText(condition.referenceFilterId())) {
            clauses.add("r.reference_filter_id = :referenceFilterId");
            params.addValue("referenceFilterId", condition.referenceFilterId());
        }
        // 배번은 그 참가 항목 한 행만 맞춘다.
        if (StringUtils.hasText(condition.bibNumber())) {
            clauses.add("e.bib_number = :bibNumber");
            params.addValue("bibNumber", condition.bibNumber().trim());
        }
        if (export != null) {
            if (StringUtils.hasText(export.organizationId())) {
                clauses.add("r.organization_id = :organizationId");
                params.addValue("organizationId", export.organizationId());
            }
            if (export.registrationIds() != null && !export.registrationIds().isEmpty()) {
                clauses.add("r.id IN (:registrationIds)");
                params.addValue("registrationIds", export.registrationIds());
            } else {
                clauses.add("r.is_del = 0");
            }
        }
        return " WHERE " + String.join(" AND ", clauses) + "\n";
    }
}
