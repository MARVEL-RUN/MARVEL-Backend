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

/** 대회 목록과 종목을 조회하며 마감 없는 대회도 표시한다. */
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
                .map(this::toEventListResponse)
                .collect(Collectors.toList());
    }

    /** 저장된 기간을 표시하고 NULL 마감은 날짜로 변환하지 않는다. */
    private EventListResponse toEventListResponse(Event event) {
        // 마감이 없으면 사용자에게 날짜 대신 제한 없음의 의미를 전달한다.
        String registrationPeriod = String.format("%s ~ %s",
                event.getRegistStartDate().format(DATE_FORMATTER),
                event.getRegistDeadline() == null ? "마감 없음" : event.getRegistDeadline().format(DATE_FORMATTER)
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
