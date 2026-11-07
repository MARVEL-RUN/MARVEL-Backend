package kr.co.teambrain.marvelrun.admin.event.query.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import kr.co.teambrain.marvelrun.admin.event.query.repository.EventCategorySouvenirQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.SouvenirQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.projection.RegistrationOptionProjection;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 버추얼 목록·엑셀이 같은 규칙으로 행 기념품을 고르도록 한 곳에 둔다. */
@Component
@RequiredArgsConstructor
public class RegistrationRowSouvenirs {

    private final RegistrationReservationHistoryResolver souvenirResolver;
    private final EventCategorySouvenirQueryRepository eventCategorySouvenirQueryRepository;
    private final SouvenirQueryRepository souvenirQueryRepository;

    /** 구매 줄의 기념품 선택 JSON을 읽는다. 형식이 깨졌으면 null이며 화면에는 확인 문구를 쓴다. */
    public List<SouvenirJson> readLine(String json) {
        try {
            return souvenirResolver.readSouvenirs(json);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    /** 종목마다 연결된 기념품 ID를 모은다. 대회의 연결 조회는 한 번만 한다. */
    public Map<String, Set<String>> linkedSouvenirIds(String eventId) {
        Map<String, Set<String>> linked = new HashMap<>();
        for (RegistrationOptionProjection option : eventCategorySouvenirQueryRepository
                .findRegistrationOptionsProjectionByEventId(eventId)) {
            linked.computeIfAbsent(option.getEventCategoryId(), key -> new HashSet<>()).add(option.getSouvenirId());
        }
        return linked;
    }

    /** 행의 기념품: 구매 줄 선택 가운데 그 행 종목에 연결된 것만 쓴다. 참가 항목이 없는 행은 줄 선택 전부, 줄을 못 읽었으면 null이다. */
    public List<SouvenirJson> rowSouvenirs(List<SouvenirJson> lineSouvenirs, String entryCategoryId,
                                           Map<String, Set<String>> linkedSouvenirIds) {
        if (lineSouvenirs == null || entryCategoryId == null) {
            return lineSouvenirs;
        }
        Set<String> linked = linkedSouvenirIds.getOrDefault(entryCategoryId, Set.of());
        return lineSouvenirs.stream()
                .filter(souvenir -> souvenir != null && linked.contains(souvenir.souvenirId()))
                .toList();
    }

    /** 기념품 ID의 이름을 한 번에 읽는다. 못 읽은 줄과 빈 ID는 건너뛴다. */
    public Map<String, String> names(List<List<SouvenirJson>> souvenirLists) {
        Set<String> ids = new LinkedHashSet<>();
        for (List<SouvenirJson> souvenirs : souvenirLists) {
            if (souvenirs == null) {
                continue;
            }
            souvenirs.stream().filter(Objects::nonNull).map(SouvenirJson::souvenirId)
                    .filter(StringUtils::hasText).forEach(ids::add);
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        return souvenirQueryRepository.findNamesByIds(new ArrayList<>(ids)).stream()
                .collect(Collectors.toMap(SouvenirQueryRepository.SouvenirNameProjection::getId,
                        SouvenirQueryRepository.SouvenirNameProjection::getName, (first, second) -> first));
    }
}
