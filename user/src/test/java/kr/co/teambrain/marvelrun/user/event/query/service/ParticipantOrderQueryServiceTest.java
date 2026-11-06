package kr.co.teambrain.marvelrun.user.event.query.service;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.query.repository.RegistrationQueryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 마이페이지 주문 상세가 토큰 주문자의 주문만 돌려주는지 본다. */
@ExtendWith(MockitoExtension.class)
class ParticipantOrderQueryServiceTest {

    @Mock private RegistrationQueryRepository repository;
    @Mock private RegistrationQueryService registrationQueries;
    @InjectMocks private ParticipantOrderQueryService service;

    @Test
    void 주문자의_주문이_아니면_없는_주문과_같은_오류로_거절한다() {
        // 주문자 키로 찾은 목록에 경로의 주문이 없으면 남의 주문이거나 없는 주문이다.
        when(repository.orderer("orderer")).thenReturn(List.of());

        assertThatThrownBy(() -> service.findOrder("someone-else-order", "orderer"))
                .isInstanceOfSatisfying(CustomException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.REGISTRATION_ACCESS_DENIED));
        verifyNoInteractions(registrationQueries);
    }
}
