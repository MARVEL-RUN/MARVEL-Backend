package kr.co.teambrain.marvelrun.admin.event.query.service;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.query.dto.response.EventCategoryResponse;
import kr.co.teambrain.marvelrun.admin.event.query.dto.response.EventListResponse;
import kr.co.teambrain.marvelrun.admin.event.query.repository.EventCategoryQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.EventQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventQueryService {

    private final EventQueryRepository eventQueryRepository;
    private final EventCategoryQueryRepository eventCategoryQueryRepository;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    /**
     * 페이징과 검색 없이 전체 대회 목록을 조회합니다.[cite: 13]
     */
    public List<EventListResponse> getEventList() {
        List<Event> events = eventQueryRepository.findAllByOrderByRegistStartDateDesc();

        return events.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    private EventListResponse convertToDto(Event event) {
        // UI 포맷에 맞춰 접수 시작일과 마감일을 'yyyy.MM.dd ~ yyyy.MM.dd' 형태로 문자열 조합[cite: 12, 13]
        String registrationPeriod = String.format("%s ~ %s",
                event.getRegistStartDate().format(DATE_FORMATTER),
                event.getRegistDeadline().format(DATE_FORMATTER)
        );

        return EventListResponse.builder()
                .eventId(event.getId())
                .eventName(event.getNameKr()) // 한글 대회명 사용[cite: 12]
                .registrationType(event.getEventStatus().name()) // 신청 유형이 없어 EventStatus(OPEN, CLOSED 등)로 임시 대체[cite: 12, 13]
                .registrationPeriod(registrationPeriod)
                .build();
    }

    public List<EventCategoryResponse> getEventCategories(String eventId) {
        return eventCategoryQueryRepository.findAllByEvent_IdOrderByOrderAsc(eventId).stream()
                .map(category -> EventCategoryResponse.builder()
                        .id(category.getId())
                        .name(category.getName()) // EventCategoryBase의 name 필드 사용[cite: 15]
                        .build())
                .collect(Collectors.toList());
    }

}