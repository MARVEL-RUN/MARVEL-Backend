package kr.co.teambrain.marvelrun.user.community.command.application.service;

import kr.co.teambrain.marvelrun.user.common.attachment.command.application.service.AnswerAttachmentCommandService;
import kr.co.teambrain.marvelrun.user.common.attachment.command.application.service.QuestionAttachmentCommandService;
import kr.co.teambrain.marvelrun.user.common.attachment.command.valid.AttachmentRelationValidator;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;


import kr.co.teambrain.marvelrun.user.community.command.application.domain.Answer;
import kr.co.teambrain.marvelrun.user.common.attachment.command.application.domain.Attachment;
import kr.co.teambrain.marvelrun.user.community.command.application.domain.Question;

import kr.co.teambrain.marvelrun.user.community.command.application.dto.*;
import kr.co.teambrain.marvelrun.user.common.attachment.command.repository.AttachmentCommandRepository;
import kr.co.teambrain.marvelrun.user.community.query.repository.AnswerCommandRepository;
import kr.co.teambrain.marvelrun.user.community.query.repository.QuestionCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;

import kr.co.teambrain.marvelrun.user.userinfo.command.Repository.UserCommandRepository;
import kr.co.teambrain.marvelrun.user.userinfo.command.application.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;


@Service
@RequiredArgsConstructor
@Slf4j
public class QuestionCommandService {
    private final QuestionCommandRepository questionCommandRepository;
    private final AnswerCommandRepository answerCommandRepository;


    private final QuestionAttachmentCommandService questionAttachmentCommandService;

    private final AnswerAttachmentCommandService answerAttachmentCommandService;

    private final PasswordEncoder encoder;

    private final UserCommandRepository userCommandRepository;
    private final EventCommandRepository eventCommandRepository;
    private final AttachmentCommandRepository attachmentCommandRepository;



    @Transactional
    public String writeQuestionArticleInEvent(
            ArticlePostRequestWrapperWithPassword requestWrapper,
            MultipartFile[] files,
            String eventId
    ) {

        ArticlePostRequest request =
                requestWrapper.getPost();


        /*
         * password 정규화.
         *
         * null / 빈 문자열 / 공백 문자열은 모두 null로 처리한다.
         */
        String rawPassword =
                requestWrapper.getPassword();

        rawPassword =
                rawPassword == null
                        || rawPassword.isBlank()
                        ? null
                        : rawPassword.trim();


        /*
         * 비회원 Question의 수정/삭제/상세 접근 검증을 위해
         * password는 필수.
         */
        if (rawPassword == null) {

            throw new CustomException(
                    ErrorCode.MUST_NEED_PASSWORD
            );
        }


        Question newQuestionArticle =
                Question.builder()
                        .title(
                                request.getTitle()
                        )
                        .content(
                                request.getContent()
                        )
                        .password(
                                encoder.encode(
                                        rawPassword
                                )
                        )

                        /*
                         * MarvelRun에서는 Question.user가 nullable.
                         *
                         * KMA V0처럼 임시 User("0000000")에
                         * 강제로 매핑하지 않는다.
                         *
                         * builder에서 생략해도 null이지만
                         * 정책을 명확하게 나타내기 위해 명시.
                         */
                        .user(null)

                        .event(
                                eventId != null
                                        ? findEventById(eventId)
                                        : null
                        )
                        .isSecret(
                                request.getSecret()
                        )
                        .isAnswered(false)
                        .authorName(
                                requestWrapper.getNickName()
                        )
                        .build();


        /*
         * Question을 먼저 영속화한다.
         *
         * 이후 QuestionAttachment가 해당 Question을 FK로 참조한다.
         */
        Question newQuestion =
                questionCommandRepository.save(
                        newQuestionArticle
                );


        /*
         * AttachmentFileValidator
         *      ↓
         * AttachmentCommandService
         *      ↓
         * 실제 Local Storage 저장
         *      ↓
         * Attachment 생성
         *      ↓
         * QuestionAttachment 생성
         *
         * 전체가 현재 Question Transaction에 참여한다.
         */
        questionAttachmentCommandService.attach(
                newQuestion,
                files
        );


        return newQuestion.getId();
    }

    // 에디터에 메타데이터(아이디, 이름, 용량) 기입해서 전달
    @Transactional
    public void patchQuestionArticleInEvent(
            ArticlePatchRequestWrapperWithPassword requestWrapper,
            MultipartFile[] newFiles,
            String questionId
    ) {

        if (requestWrapper.getPassword() == null) {

            throw new CustomException(
                    ErrorCode.MUST_NEED_PASSWORD
            );
        }


        ArticlePatchRequest request =
                requestWrapper.getPatch();


        Question targetQuestion =
                findById(
                        questionId
                );


        /*
         * 비밀번호 검증
         */
        if (!encoder.matches(
                requestWrapper.getPassword(),
                targetQuestion.getPassword()
        )) {

            throw new CustomException(
                    ErrorCode.NOT_OWNER_QUESTION_ARTICLE
            );
        }


        /*
         * 답변 완료 이후 수정 금지
         */
        if (targetQuestion.getIsAnswered()) {

            throw new CustomException(
                    ErrorCode.ALREADY_ANSWERED_QUESTION
            );
        }


        List<String> deletedAttachmentIds =
                request != null
                        && request.getDeletedAttachmentIds() != null

                        ? request.getDeletedAttachmentIds()

                        : List.of();


        questionAttachmentCommandService
                .patchAttachments(
                        targetQuestion,
                        deletedAttachmentIds,
                        newFiles
                );


        targetQuestion.patchContent(
                request
        );
    }

    @Transactional
    public void mappingQuestionOwnerUserToDeletedUser(User deleteTargetUser) {
        User deleteMappedUser = this.findDeleteMappedUserById();
        List<Question> questionList = questionCommandRepository.findAllByUserId(deleteTargetUser.getId());

        for(Question question : questionList) {
            question.mappedDeleteuser(deleteMappedUser);

            questionCommandRepository.save(question);
        }
    }

    @Transactional
    public void deleteQuestionArticle(
            ArticleDeleteRequestWithPassword request,
            String questionId
    ) {

        /*
         * password 필수 검증
         */
        if (request.getPassword() == null
                || request.getPassword().isBlank()) {

            throw new CustomException(
                    ErrorCode.QUESTION_PASSWORD_REQUIRED
            );
        }


        /*
         * Question 존재 검증
         */
        Question targetQuestion =
                findById(
                        questionId
                );


        /*
         * Question password 검증
         */
        if (!encoder.matches(
                request.getPassword().trim(),
                targetQuestion.getPassword()
        )) {

            throw new CustomException(
                    ErrorCode.INVALID_QUESTION_PASSWORD
            );
        }


        /*
         * Answer 존재 여부 확인.
         *
         * Answer가 있다면 Question보다 먼저 제거해야 한다.
         */
        Optional<Answer> targetAnswerOpt =
                answerCommandRepository
                        .findByQuestionId(
                                questionId
                        );


        if (targetAnswerOpt.isPresent()) {

            Answer answer =
                    targetAnswerOpt.get();


            /*
             * AnswerAttachment 관계
             * +
             * Attachment metadata
             * 삭제.
             *
             * 실제 binary는 commit 이후 제거.
             */
            answerAttachmentCommandService
                    .deleteAllByAnswer(
                            answer
                    );


            /*
             * Answer 자체 삭제.
             */
            answerCommandRepository.delete(
                    answer
            );


            /*
             * Answer -> Question FK를 먼저 실제 DB에서 제거.
             */
            answerCommandRepository.flush();
        }


        /*
         * QuestionAttachment 관계
         * +
         * Attachment metadata 삭제.
         */
        questionAttachmentCommandService
                .deleteAllByQuestion(
                        targetQuestion
                );


        /*
         * 마지막으로 Question 삭제.
         */
        questionCommandRepository.delete(
                targetQuestion
        );
    }



    private Question findById(String id) {
        return questionCommandRepository.findById(id)
                .orElseThrow(()-> new CustomException(ErrorCode.QUESTION_NOT_FOUND));
    }

    private User findUserById(String userId) {
        return userCommandRepository.findById(userId)
                .orElseThrow(()-> new CustomException(ErrorCode.USER_NOT_FOUND));
    }

    private User findDeleteMappedUserById() {
        return userCommandRepository.findById("000000")
                .orElseThrow(()-> new CustomException(ErrorCode.MUST_NEED_DELETE_MAP_USER));
    }

    private Event findEventById(String eventId) {
        return eventCommandRepository.findById(eventId)
                .orElseThrow(()-> new CustomException(ErrorCode.EVENT_NOT_FOUND));
    }
}
