package kr.co.teambrain.marvelrun.user.community.query.service;

import kr.co.teambrain.marvelrun.user.common.dto.PasswordInputRequest;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.community.command.application.domain.Question;
import kr.co.teambrain.marvelrun.user.community.query.domain.QuestionSearchTarget;
import kr.co.teambrain.marvelrun.user.community.query.domain.QuestionSortType;
import kr.co.teambrain.marvelrun.user.community.query.dto.AnswerHeader;
import kr.co.teambrain.marvelrun.user.community.query.dto.AnswerHeaderProjection;
import kr.co.teambrain.marvelrun.user.community.query.dto.QuestionHeader;
import kr.co.teambrain.marvelrun.user.community.query.dto.QuestionProjection;
import kr.co.teambrain.marvelrun.user.community.query.dto.response.QuestionAnswerResponse;
import kr.co.teambrain.marvelrun.user.community.query.dto.response.QuestionDetailResponse;
import kr.co.teambrain.marvelrun.user.community.query.repository.AnswerQueryRepository;
import kr.co.teambrain.marvelrun.user.community.query.repository.QuestionQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QuestionQueryService {

    private final QuestionQueryRepository
            questionQueryRepository;

    private final AnswerQueryRepository
            answerQueryRepository;

    private final PasswordEncoder encoder;


    /**
     * Question 목록 / 검색.
     */
    public Page<QuestionAnswerResponse> getQuestionPage(
            String eventId,
            QuestionSearchTarget target,
            String keyword,
            Pageable pageable
    ) {

        QuestionSearchTarget normalizedTarget =
                target == null
                        ? QuestionSearchTarget.ALL
                        : target;


        String normalizedKeyword =
                keyword == null
                        ? ""
                        : keyword.strip();


        Page<QuestionProjection> questionPage =
                questionQueryRepository.search(
                        normalizeEventId(eventId),
                        normalizedTarget.name(),
                        normalizedKeyword,
                        pageable
                );


        if (questionPage.isEmpty()) {

            return new PageImpl<>(
                    List.of(),
                    pageable,
                    0
            );
        }


        List<String> questionIds =
                questionPage.getContent()
                        .stream()
                        .filter(
                                question ->
                                        Boolean.TRUE.equals(
                                                question.getIsAnswered()
                                        )
                        )
                        .map(
                                QuestionProjection::getId
                        )
                        .toList();


        Map<String, AnswerHeaderProjection> answerMap =
                findAnswerHeaderMap(
                        questionIds
                );


        long startNo =
                questionPage.getTotalElements()
                        - pageable.getOffset();


        List<QuestionAnswerResponse> responseList =
                new java.util.ArrayList<>(
                        questionPage.getNumberOfElements()
                );


        for (int i = 0;
             i < questionPage.getContent().size();
             i++) {

            QuestionProjection question =
                    questionPage.getContent().get(i);


            long no =
                    startNo - i;


            QuestionHeader questionHeader =
                    new QuestionHeader(
                            no,
                            question.getId(),
                            question.getTitle(),
                            question.getAuthorName(),
                            question.getCreatedAt(),
                            Boolean.TRUE.equals(
                                    question.getIsSecret()
                            ),
                            Boolean.TRUE.equals(
                                    question.getIsAnswered()
                            )
                    );


            AnswerHeader answerHeader =
                    null;


            if (Boolean.TRUE.equals(
                    question.getIsAnswered()
            )) {

                AnswerHeaderProjection answer =
                        answerMap.get(
                                question.getId()
                        );


                /*
                 * isAnswered=true인데 Answer가 없다면
                 * 도메인 상태가 깨진 것.
                 */
                if (answer == null) {

                    throw new CustomException(
                            ErrorCode.ANSWER_NOT_FOUND
                    );
                }


                answerHeader =
                        new AnswerHeader(
                                no,
                                answer.id(),
                                answer.title(),
                                answer.authorName(),
                                answer.createdAt()
                        );
            }


            responseList.add(
                    QuestionAnswerResponse.builder()
                            .questionHeader(
                                    questionHeader
                            )
                            .answerHeader(
                                    answerHeader
                            )
                            .build()
            );
        }


        return new PageImpl<>(
                responseList,
                pageable,
                questionPage.getTotalElements()
        );
    }


    /**
     * Question 상세 조회.
     * <p>
     * 공개글은 password 없이 조회 가능.
     * <p>
     * 비밀글만 Question password를 검증한다.
     */
    public QuestionDetailResponse getQuestionDetail(
            PasswordInputRequest passwordRequest,
            String questionId
    ) {

        Question question =
                findQuestionById(
                        questionId
                );


        validateSecretQuestionPassword(
                question,
                passwordRequest
        );


        return QuestionDetailResponse.builder()
                .id(
                        question.getId()
                )
                .title(
                        question.getTitle()
                )
                .content(
                        question.getContent()
                )
                .author(
                        question.getAuthorName()
                )
                .createdAt(
                        question.getCreatedAt()
                )
                .isSecret(
                        question.getIsSecret()
                )
                .build();
    }


    private Map<String, AnswerHeaderProjection>
    findAnswerHeaderMap(
            List<String> questionIds
    ) {

        if (questionIds.isEmpty()) {
            return Map.of();
        }


        return answerQueryRepository
                .findHeadersByQuestionIds(
                        questionIds
                )
                .stream()
                .collect(
                        Collectors.toMap(
                                AnswerHeaderProjection::questionId,
                                Function.identity()
                        )
                );
    }


    private void validateSecretQuestionPassword(
            Question question,
            PasswordInputRequest request
    ) {

        if (!question.getIsSecret()) {
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
                question.getPassword()
        )) {

            throw new CustomException(
                    ErrorCode.INVALID_QUESTION_PASSWORD
            );
        }
    }


    private Question findQuestionById(
            String questionId
    ) {

        return questionQueryRepository
                .findById(
                        questionId
                )
                .orElseThrow(
                        () -> new CustomException(
                                ErrorCode.QUESTION_NOT_FOUND
                        )
                );
    }


    private String normalizeEventId(
            String eventId
    ) {

        if (eventId == null
                || eventId.isBlank()) {

            return null;
        }


        return eventId;
    }
}