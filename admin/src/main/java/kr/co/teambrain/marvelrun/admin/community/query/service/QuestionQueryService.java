package kr.co.teambrain.marvelrun.admin.community.query.service;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.community.command.application.domain.Question;
import kr.co.teambrain.marvelrun.admin.community.query.domain.QuestionSearchTarget;
import kr.co.teambrain.marvelrun.admin.community.query.domain.QuestionSortType;
import kr.co.teambrain.marvelrun.admin.community.query.dto.projection.AdminQuestionAnswerProjection;
import kr.co.teambrain.marvelrun.admin.community.query.dto.response.AnswerDetailResponse;
import kr.co.teambrain.marvelrun.admin.community.query.dto.response.QuestionAndAnswerDetailResponse;
import kr.co.teambrain.marvelrun.admin.community.query.dto.response.QuestionAndAnswerResponse;
import kr.co.teambrain.marvelrun.admin.community.query.dto.response.QuestionDetailResponse;
import kr.co.teambrain.marvelrun.admin.community.query.repository.AnswerQueryRepository;
import kr.co.teambrain.marvelrun.admin.community.query.repository.QuestionQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QuestionQueryService {

    private final QuestionQueryRepository
            questionQueryRepository;

    private final AnswerQueryRepository
            answerQueryRepository;


    public Page<QuestionAndAnswerResponse> readQuestions(
            String eventId,
            QuestionSearchTarget target,
            String keyword,
            boolean isAnswered,
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


        String normalizedEventId =
                eventId == null
                        || eventId.isBlank()
                        ? null
                        : eventId;


        Page<AdminQuestionAnswerProjection> questionPage =
                questionQueryRepository.searchQuestions(
                        normalizedEventId,
                        normalizedTarget.name(),
                        normalizedKeyword,
                        isAnswered,
                        pageable
                );


        return mapPage(
                questionPage,
                pageable
        );
    }


    public Page<QuestionAndAnswerResponse> readHomepageQuestions(
            QuestionSearchTarget target,
            String keyword,
            Boolean isAnswered,
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


        Page<AdminQuestionAnswerProjection> page =
                questionQueryRepository.searchHomepageQuestions(
                        normalizedTarget.name(),
                        normalizedKeyword,
                        isAnswered,
                        pageable
                );


        return mapPage(
                page,
                pageable
        );
    }


    public QuestionAndAnswerDetailResponse readQuestionDetail(
            String questionId
    ) {

        Question question =
                questionQueryRepository
                        .findById(
                                questionId
                        )
                        .orElseThrow(
                                () -> new CustomException(
                                        ErrorCode.QUESTION_NOT_FOUND
                                )
                        );


        QuestionDetailResponse questionDetail =
                QuestionDetailResponse.builder()
                        .id(
                                question.getId()
                        )
                        .title(
                                question.getTitle()
                        )
                        .author(
                                question.getAuthorName()
                        )
                        .content(
                                question.getContent()
                        )
                        .createdAt(
                                question.getCreatedAt()
                        )
                        .secret(
                                question.getIsSecret()
                        )
                        .answered(
                                question.getIsAnswered()
                        )
                        .build();


        AnswerDetailResponse answerDetail =
                answerQueryRepository
                        .findDetailByQuestionId(
                                questionId
                        )
                        .map(
                                projection ->
                                        new AnswerDetailResponse(
                                                projection.getId(),
                                                projection.getTitle(),
                                                projection.getContent(),
                                                projection.getAuthor(),
                                                projection.getCreatedAt()
                                        )
                        )
                        .orElse(null);


        return new QuestionAndAnswerDetailResponse(
                questionDetail,
                answerDetail
        );
    }


    private Page<QuestionAndAnswerResponse> mapPage(
            Page<AdminQuestionAnswerProjection> questionPage,
            Pageable pageable
    ) {

        long startNo =
                questionPage.getTotalElements()
                        - pageable.getOffset();


        List<QuestionAndAnswerResponse> content =
                new ArrayList<>(
                        questionPage.getNumberOfElements()
                );


        for (int i = 0;
             i < questionPage.getContent().size();
             i++) {

            AdminQuestionAnswerProjection row =
                    questionPage.getContent().get(i);


            content.add(
                    QuestionAndAnswerResponse.builder()
                            .no(
                                    startNo - i
                            )
                            .questionId(
                                    row.getQuestionId()
                            )
                            .questionTitle(
                                    row.getQuestionTitle()
                            )
                            .authorName(
                                    row.getAuthorName()
                            )
                            .questionCreatedAt(
                                    row.getQuestionCreatedAt()
                            )
                            .secret(
                                    Boolean.TRUE.equals(
                                            row.getSecret()
                                    )
                            )
                            .answered(
                                    Boolean.TRUE.equals(
                                            row.getAnswered()
                                    )
                            )
                            .eventId(
                                    row.getEventId()
                            )
                            .answerId(
                                    row.getAnswerId()
                            )
                            .answerTitle(
                                    row.getAnswerTitle()
                            )
                            .answerAuthorName(
                                    row.getAnswerAuthorName()
                            )
                            .answerCreatedAt(
                                    row.getAnswerCreatedAt()
                            )
                            .build()
            );
        }


        return new PageImpl<>(
                content,
                pageable,
                questionPage.getTotalElements()
        );
    }
}