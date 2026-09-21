package kr.co.teambrain.marvelrun.admin.community.query.service;


import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.community.query.dto.projection.AdminAnswerDetailProjection;
import kr.co.teambrain.marvelrun.admin.community.query.dto.response.AnswerDetailResponse;
import kr.co.teambrain.marvelrun.admin.community.query.repository.AnswerQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnswerQueryService {

    private final AnswerQueryRepository
            answerQueryRepository;


    public AnswerDetailResponse readAnswerDetail(
            String answerId
    ) {

        AdminAnswerDetailProjection answer =
                answerQueryRepository
                        .findDetailById(
                                answerId
                        )
                        .orElseThrow(
                                () -> new CustomException(
                                        ErrorCode.ANSWER_NOT_FOUND
                                )
                        );


        return new AnswerDetailResponse(
                answer.getId(),
                answer.getTitle(),
                answer.getContent(),
                answer.getAuthor(),
                answer.getCreatedAt()
        );
    }
}