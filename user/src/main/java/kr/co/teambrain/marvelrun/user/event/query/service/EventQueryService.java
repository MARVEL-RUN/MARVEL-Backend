package kr.co.teambrain.marvelrun.user.event.query.service;


import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.query.assembler.RegistrationOptionResponseAssembler;
import kr.co.teambrain.marvelrun.user.event.query.dto.response.RegistrationOptionResponse;
import kr.co.teambrain.marvelrun.user.event.query.repository.EventCategorySouvenirQueryRepository;
import kr.co.teambrain.marvelrun.user.event.query.repository.EventQueryRepository;
import kr.co.teambrain.marvelrun.user.event.query.repository.projection.RegistrationOptionProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventQueryService {

    private final EventQueryRepository
            eventQueryRepository;

    private final EventCategorySouvenirQueryRepository
            eventCategorySouvenirQueryRepository;

    private final RegistrationOptionResponseAssembler
            registrationOptionResponseAssembler;


    public RegistrationOptionResponse getRegistrationOptions(
            String eventId
    ) {

        /*
         * Event Entity는 필요하지 않다.
         *
         * 존재 여부만 필요하므로 existsById 사용.
         *
         * 향후 EventVisibilityInterceptor가
         * 존재 여부까지 확실히 보장한다면
         * 이 조회는 제거 가능.
         */
        if (
                !eventQueryRepository.existsById(
                        eventId
                )
        ) {

            throw new CustomException(
                    ErrorCode.EVENT_NOT_FOUND
            );
        }


        /*
         * DB 조회 결과는 flat projection.
         */
        List<RegistrationOptionProjection> projections =
                eventCategorySouvenirQueryRepository
                        .findRegistrationOptionsProjectionByEventId(
                                eventId
                        );


        /*
         * Projection → API Response 조립은
         * Assembler 책임.
         */
        return registrationOptionResponseAssembler
                .assemble(
                        projections
                );
    }
}

