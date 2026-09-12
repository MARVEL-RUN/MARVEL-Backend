package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** registration 생성이 가능한지에 대한 여부를 검증*/
@Component
public class RegistrationValidator {

    public void validateEventRegistrable(
            Event event
    ) {

        LocalDateTime now =
                LocalDateTime.now();

        if (event.getRegistStartDate() != null
                && now.isBefore(
                event.getRegistStartDate()
        )) {

            throw new CustomException(
                    ErrorCode.EVENT_REGISTRATION_NOT_STARTED
            );
        }

        if (now.isAfter(
                event.getRegistDeadline()
        )) {

            throw new CustomException(
                    ErrorCode.EVENT_REGISTRATION_CLOSED
            );
        }
    }


    public void validateCategory(
            Event event,
            EventCategory category
    ) {

        if (!category.getEvent()
                .getId()
                .equals(event.getId())) {

            throw new CustomException(
                    ErrorCode.INVALID_EVENT_CATEGORY
            );
        }

        if (!Boolean.TRUE.equals(
                category.getIsActive()
        )) {

            throw new CustomException(
                    ErrorCode.INACTIVE_EVENT_CATEGORY
            );
        }
    }
}