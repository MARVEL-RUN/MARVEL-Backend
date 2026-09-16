package kr.co.teambrain.marvelrun.user.community.query.service;

import kr.co.teambrain.marvelrun.user.common.dto.PasswordInputRequest;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.community.query.dto.AnswerDetail;
import kr.co.teambrain.marvelrun.user.community.query.dto.response.AnswerDetailResponse;
import kr.co.teambrain.marvelrun.user.community.query.repository.AnswerQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnswerQueryService {

    private final PasswordEncoder encoder;

    private final AnswerQueryRepository
            answerQueryRepository;


    public AnswerDetailResponse getAnswerDetail(
            PasswordInputRequest passwordRequest,
            String answerId
    ) {

        AnswerDetail answer =
                answerQueryRepository
                        .findAnswerDetailById(
                                answerId
                        )
                        .orElseThrow(
                                () -> new CustomException(
                                        ErrorCode.ANSWER_NOT_FOUND
                                )
                        );


        validatePasswordIfSecret(
                answer,
                passwordRequest
        );


        return new AnswerDetailResponse(
                answer.answerId(),
                answer.title(),
                answer.content(),
                answer.author(),
                answer.createdAt(),
                answer.isSecret()
        );
    }


    private void validatePasswordIfSecret(
            AnswerDetail answer,
            PasswordInputRequest request
    ) {

        if (!answer.isSecret()) {
            return;
        }


        if (request == null
                || request.password() == null
                || request.password().isBlank()) {

            throw new CustomException(
                    ErrorCode.QUESTION_PASSWORD_REQUIRED
            );
        }


        if (!encoder.matches(
                request.password().trim(),
                answer.questionPassword()
        )) {

            throw new CustomException(
                    ErrorCode.INVALID_QUESTION_PASSWORD
            );
        }
    }
}