package kr.co.teambrain.marvelrun.admin.community.command.application.service;

import kr.co.teambrain.marvelrun.admin.common.dto.response.PasswordInputRequest;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.community.command.application.domain.Question;
import kr.co.teambrain.marvelrun.admin.community.command.repository.AnswerCommandRepository;
import kr.co.teambrain.marvelrun.admin.community.command.repository.QuestionCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class QuestionCommandService {

    private final QuestionCommandRepository
            questionCommandRepository;

    private final AnswerCommandRepository
            answerCommandRepository;

    private final PasswordEncoder
            passwordEncoder;


    @Transactional
    public void deleteQuestion(
            String questionId
    ) {

        Question question =
                questionCommandRepository
                        .findByIdForUpdate(
                                questionId
                        )
                        .orElseThrow(
                                () -> new CustomException(
                                        ErrorCode.QUESTION_NOT_FOUND
                                )
                        );


        answerCommandRepository
                .findByQuestion_Id(
                        questionId
                )
                .ifPresent(
                        answerCommandRepository::delete
                );


        questionCommandRepository.delete(
                question
        );
    }


    /** 관리자가 패스워드 강제 업데이트 */
    @Transactional
    public void updatePassword(
            String questionId,
            PasswordInputRequest request
    ) {

        Question question =
                questionCommandRepository
                        .findById(
                                questionId
                        )
                        .orElseThrow(
                                () -> new CustomException(
                                        ErrorCode.QUESTION_NOT_FOUND
                                )
                        );


        question.updatePassword(
                passwordEncoder.encode(
                        request.getPassword()
                )
        );
    }
}