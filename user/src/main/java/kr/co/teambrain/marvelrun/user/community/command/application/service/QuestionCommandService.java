package kr.co.teambrain.marvelrun.user.community.command.application.service;

import kr.co.teambrain.marvelrun.user.common.dto.PasswordInputRequest;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.community.command.application.domain.Answer;
import kr.co.teambrain.marvelrun.user.community.command.application.domain.CommunityConstants;
import kr.co.teambrain.marvelrun.user.community.command.application.domain.Question;
import kr.co.teambrain.marvelrun.user.community.command.application.dto.ArticleDeleteRequestWithPassword;
import kr.co.teambrain.marvelrun.user.community.command.application.dto.ArticlePatchRequest;
import kr.co.teambrain.marvelrun.user.community.command.application.dto.ArticlePatchRequestWrapperWithPassword;
import kr.co.teambrain.marvelrun.user.community.command.application.dto.ArticlePostRequest;
import kr.co.teambrain.marvelrun.user.community.command.application.dto.ArticlePostRequestWrapperWithPassword;
import kr.co.teambrain.marvelrun.user.community.command.repository.AnswerCommandRepository;
import kr.co.teambrain.marvelrun.user.community.command.repository.QuestionCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.user.userinfo.command.Repository.UserCommandRepository;
import kr.co.teambrain.marvelrun.user.userinfo.command.application.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class QuestionCommandService {

    private final PasswordEncoder encoder;

    private final QuestionCommandRepository
            questionCommandRepository;

    private final AnswerCommandRepository
            answerCommandRepository;

    private final EventCommandRepository
            eventCommandRepository;

    private final UserCommandRepository
            userCommandRepository;


    /**
     * 문의글 작성.
     *
     * password는 비밀글 여부와 무관하게
     * 수정/삭제 검증을 위해 항상 필요하다.
     *
     * MarvelRun에서는 비회원 Question을
     * 임시 User에 강제로 매핑하지 않는다.
     */
    @Transactional
    public String writeQuestionArticleInEvent(
            ArticlePostRequestWrapperWithPassword requestWrapper,
            String eventId
    ) {

        ArticlePostRequest request =
                requestWrapper.getPost();


        String rawPassword =
                normalizeRequiredPassword(
                        requestWrapper.getPassword()
                );


        Event event =
                eventId == null
                        || eventId.isBlank()
                        ? null
                        : findEventById(eventId);


        User guestUser =
                userCommandRepository
                        .findById(
                                CommunityConstants.GUEST_USER_ID
                        )
                        .orElseThrow(
                                () -> new CustomException(
                                        ErrorCode.MUST_NEED_GUEST_NAMED_USER
                                )
                        );

        Question question =
                Question.builder()
                        .title(request.getTitle())
                        .content(request.getContent())
                        .password(
                                encoder.encode(rawPassword)
                        )
                        .user(guestUser)
                        .event(event)
                        .isSecret(request.getSecret())
                        .isAnswered(false)
                        .authorName(
                                requestWrapper.getNickName()
                        )
                        .build();


        Question savedQuestion =
                questionCommandRepository.save(
                        question
                );


        return savedQuestion.getId();
    }


    /**
     * 문의글 수정.
     *
     * 답변 완료 전까지만 가능하다.
     */
    @Transactional
    public void patchQuestionArticleInEvent(
            ArticlePatchRequestWrapperWithPassword requestWrapper,
            String questionId
    ) {

        String rawPassword =
                normalizeRequiredPassword(
                        requestWrapper.getPassword()
                );


        Question targetQuestion =
                findById(
                        questionId
                );


        validatePassword(
                rawPassword,
                targetQuestion
        );


        if (targetQuestion.getIsAnswered()) {

            throw new CustomException(
                    ErrorCode.QUESTION_ALREADY_ANSWERED
            );
        }


        ArticlePatchRequest request =
                requestWrapper.getPatch();


        targetQuestion.patchContent(
                request
        );
    }


    /**
     * 문의글 삭제.
     *
     * KMA 정책을 계승하여
     * 답변이 완료된 문의도 password가 일치하면 삭제 가능하다.
     *
     * Answer가 존재하면 FK 관계상 Answer를 먼저 삭제한다.
     */
    @Transactional
    public void deleteQuestionArticle(
            PasswordInputRequest request,
            String questionId
    ) {

        String rawPassword =
                normalizeRequiredPassword(
                        request.password()
                );


        Question targetQuestion =
                findById(
                        questionId
                );


        validatePassword(
                rawPassword,
                targetQuestion
        );


        Optional<Answer> answerOptional =
                answerCommandRepository
                        .findByQuestionId(
                                questionId
                        );


        answerOptional.ifPresent(
                answerCommandRepository::delete
        );


        questionCommandRepository.delete(
                targetQuestion
        );
    }


    private void validatePassword(
            String rawPassword,
            Question question
    ) {

        if (!encoder.matches(
                rawPassword,
                question.getPassword()
        )) {

            throw new CustomException(
                    ErrorCode.INVALID_QUESTION_PASSWORD
            );
        }
    }


    private String normalizeRequiredPassword(
            String password
    ) {

        if (password == null
                || password.isBlank()) {

            throw new CustomException(
                    ErrorCode.QUESTION_PASSWORD_REQUIRED
            );
        }


        return password.trim();
    }


    private Question findById(
            String questionId
    ) {

        return questionCommandRepository
                .findById(
                        questionId
                )
                .orElseThrow(
                        () -> new CustomException(
                                ErrorCode.QUESTION_NOT_FOUND
                        )
                );
    }


    private Event findEventById(
            String eventId
    ) {

        return eventCommandRepository
                .findById(
                        eventId
                )
                .orElseThrow(
                        () -> new CustomException(
                                ErrorCode.EVENT_NOT_FOUND
                        )
                );
    }
}