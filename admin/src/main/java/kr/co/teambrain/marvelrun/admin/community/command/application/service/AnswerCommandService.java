package kr.co.teambrain.marvelrun.admin.community.command.application.service;




import kr.co.teambrain.marvelrun.admin.auth.command.application.domain.Admin;

import kr.co.teambrain.marvelrun.admin.auth.command.repository.AdminCommandRepository;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.community.command.application.domain.Answer;
import kr.co.teambrain.marvelrun.admin.community.command.application.domain.Question;
import kr.co.teambrain.marvelrun.admin.community.command.application.dto.AnswerRequest;
import kr.co.teambrain.marvelrun.admin.community.command.application.dto.AnswerUpdate;
import kr.co.teambrain.marvelrun.admin.community.command.repository.AnswerCommandRepository;
import kr.co.teambrain.marvelrun.admin.community.command.repository.QuestionCommandRepository;
import kr.co.teambrain.marvelrun.admin.security.util.AdminInfoUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AnswerCommandService {

    private final QuestionCommandRepository
            questionCommandRepository;

    private final AnswerCommandRepository
            answerCommandRepository;

    private final AdminCommandRepository
            adminCommandRepository;


    /**
     * Answer 생성.
     *
     * 동일 Question에 여러 Admin이 동시에 답변하는 것을 막기 위해
     * Question row를 PESSIMISTIC_WRITE로 잠근다.
     */
    @Transactional
    public String createAnswer(
            AnswerRequest answerRequest,
            String questionId
    ) {

        Question question =
                getQuestionForUpdate(
                        questionId
                );


        if (Boolean.TRUE.equals(
                question.getIsAnswered()
        )) {

            throw new CustomException(
                    ErrorCode.QUESTION_ALREADY_ANSWERED
            );
        }


        Admin admin =
                getAdmin();


        Answer answer =
                Answer.builder()
                        .admin(admin)
                        .question(question)
                        .title(
                                answerRequest.getTitle()
                        )
                        .content(
                                answerRequest.getContent()
                        )
                        .build();


        Answer savedAnswer =
                answerCommandRepository.save(
                        answer
                );


        /*
         * 현재 transaction에서 관리 중인 Question이므로
         * 별도의 save(question)는 필요 없다.
         *
         * dirty checking으로 UPDATE 된다.
         */
        question.resolveQuestion();


        return savedAnswer.getId();
    }


    /**
     * Answer 수정.
     *
     * 현재 정책에서는 어느 인증 Admin이든 수정 가능.
     */
    @Transactional
    public void updateAnswer(
            AnswerUpdate answerUpdate,
            String answerId
    ) {

        Answer answer =
                getAnswer(
                        answerId
                );


        answer.updateAnswer(
                answerUpdate
        );
    }


    /**
     * Answer 삭제.
     *
     * Answer 삭제와 Question.isAnswered=false를
     * 하나의 transaction으로 처리한다.
     */
    @Transactional
    public void deleteAnswer(
            String answerId
    ) {

        String questionId =
                answerCommandRepository
                        .findQuestionIdByAnswerId(
                                answerId
                        )
                        .orElseThrow(
                                () -> new CustomException(
                                        ErrorCode.ANSWER_NOT_FOUND
                                )
                        );


        /*
         * 생성과 삭제가 동일 Question row lock을 공유한다.
         */
        Question question =
                getQuestionForUpdate(
                        questionId
                );


        /*
         * Question lock 획득 후 Answer를 다시 확인한다.
         */
        Answer answer =
                getAnswer(
                        answerId
                );


        answerCommandRepository.delete(
                answer
        );


        question.reopenQuestion();
    }

    /**
     * Question 삭제.
     *
     * 연결된 Answer가 존재하면 Answer를 먼저 삭제한 뒤
     * Question을 삭제한다.
     *
     * Answer 생성/삭제와 동일하게 Question row lock을 기준으로
     * 동시성을 제어한다.
     */
    @Transactional
    public void deleteQuestionAndAnswer(
            String questionId
    ) {

        /*
         * 해당 Question에 대한 Answer 생성/삭제와
         * 동일한 row lock을 획득한다.
         */
        Question question =
                getQuestionForUpdate(
                        questionId
                );


        /*
         * Answer가 존재하는 경우 먼저 삭제한다.
         *
         * Answer.question FK가 Question을 참조하므로
         * Question보다 Answer를 먼저 삭제해야 한다.
         */
        answerCommandRepository
                .findByQuestion_Id(questionId)
                .ifPresent(answer -> {
                    answerCommandRepository.delete(answer);
                    answerCommandRepository.flush();
                });

        questionCommandRepository.delete(question);
    }



    private Admin getAdmin() {

        return adminCommandRepository
                .findById(
                        AdminInfoUtil.getAdminId()
                )
                .orElseThrow(
                        () -> new CustomException(
                                ErrorCode.ADMIN_NOT_FOUND
                        )
                );
    }


    private Question getQuestionForUpdate(
            String questionId
    ) {
        // 대상 question에 대해 락을 걸고 가져와 삭제처리한다
        return questionCommandRepository
                .findByIdForUpdate(
                        questionId
                )
                .orElseThrow(
                        () -> new CustomException(
                                ErrorCode.QUESTION_NOT_FOUND
                        )
                );
    }


    private Answer getAnswer(
            String answerId
    ) {

        return answerCommandRepository
                .findById(
                        answerId
                )
                .orElseThrow(
                        () -> new CustomException(
                                ErrorCode.ANSWER_NOT_FOUND
                        )
                );
    }
}