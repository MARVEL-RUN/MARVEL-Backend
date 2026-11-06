package kr.co.teambrain.marvelrun.user.event.command.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationCapacityService;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.redis.RedisService;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.policy.EventRegistrationPolicy;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.ParticipantAddressModifyRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.ParticipantOrdererModifyRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.PersonalPasswordChangeRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationInformationPolicyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationPolicyValidator;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventRegistrationPolicyRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyService;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 마이페이지 주소·주문자 정보·비밀번호 수정이 주문자 단위로 반영되는지 본다. */
@ExtendWith(MockitoExtension.class)
class ParticipantOrderModificationServiceTest {

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 12, 0);

    @Mock private EntityManager entityManager;
    @Mock private ServerTimeProvider time;
    @Mock private RegistrationCommandRepository registrationRepository;
    @Mock private EventRegistrationPolicyRepository eventPolicyRepository;
    @Mock private RegistrationCapacityService registrationCapacityService;
    @Mock private RegistrationInformationPolicyValidator informationPolicyValidator;
    @Mock private RegistrationActionPolicyService actionPolicies;
    @Mock private RegistrationPolicyValidator policyValidator;
    @Mock private RedisService redisService;

    private final Event event = Event.builder().id("event").build();
    private ParticipantOrderModificationService service;

    @BeforeEach
    void setUp() {
        lenient().when(time.currentDateTime()).thenReturn(NOW);
        service = new ParticipantOrderModificationService(entityManager, time, registrationRepository,
                eventPolicyRepository, registrationCapacityService, informationPolicyValidator, actionPolicies,
                policyValidator, ENCODER, redisService);
    }

    @Test
    void 주소는_자기_주문만_바꾼다() {
        Registration registration = order("r1", false, "Test1234!");
        when(entityManager.find(Registration.class, "r1", LockModeType.PESSIMISTIC_WRITE)).thenReturn(registration);

        assertError(() -> service.modifyAddress("r1", "other", new ParticipantAddressModifyRequest("서울", "1층")),
                ErrorCode.REGISTRATION_ACCESS_DENIED);
        assertThat(registration.getAddress()).isNull();

        when(eventPolicyRepository.findByEventId("event")).thenReturn(Optional.empty());
        service.modifyAddress("r1", "orderer", new ParticipantAddressModifyRequest(" 서울 ", "1층"));

        assertThat(registration.getAddress()).isEqualTo("서울");
        assertThat(registration.getAddressDetail()).isEqualTo("1층");
        verify(informationPolicyValidator).validateRegistrationModificationPeriod(event, NOW);
    }

    @Test
    void 주소_필수_대회는_주소를_비울_수_없다() {
        when(entityManager.find(Registration.class, "r1", LockModeType.PESSIMISTIC_WRITE))
                .thenReturn(order("r1", false, "Test1234!"));
        EventRegistrationPolicy policy = mock(EventRegistrationPolicy.class);
        when(policy.getAddressRequired()).thenReturn(true);
        when(eventPolicyRepository.findByEventId("event")).thenReturn(Optional.of(policy));

        assertError(() -> service.modifyAddress("r1", "orderer", new ParticipantAddressModifyRequest(" ", null)),
                ErrorCode.REGISTRATION_ADDRESS_REQUIRED);
    }

    @Test
    void 주문자_정보는_취소한_주문까지_모두_바꾸고_검사는_살아_있는_주문만_한다() {
        Registration live = order("r1", false, "Test1234!");
        Registration canceled = order("r2", true, "Test1234!");
        orders(live, canceled);
        when(registrationRepository.countOtherOrdererActiveByIdentityForShare(
                "event", "orderer", "김새이름", "01099998888", "1990-01-01")).thenReturn(0L);

        service.modifyOrderer("orderer", new ParticipantOrdererModifyRequest("김새이름", "01099998888", "1990-01-01", GenderClass.F));

        for (Registration order : List.of(live, canceled)) {
            assertThat(order.getName()).isEqualTo("김새이름");
            assertThat(order.getPhNum()).isEqualTo("01099998888");
            assertThat(order.getGender()).isEqualTo(GenderClass.F);
        }
        verify(registrationCapacityService).lockEvent("event");
        verify(informationPolicyValidator).validateStatus(live);
        verify(informationPolicyValidator, never()).validateStatus(canceled);
    }

    @Test
    void 바꿀_신원을_다른_주문자가_쓰면_아무것도_바꾸지_않는다() {
        Registration live = order("r1", false, "Test1234!");
        orders(live);
        when(registrationRepository.countOtherOrdererActiveByIdentityForShare(
                anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(1L);

        assertError(() -> service.modifyOrderer("orderer",
                new ParticipantOrdererModifyRequest("김새이름", "01099998888", "1990-01-01", GenderClass.F)),
                ErrorCode.REGISTRATION_ALREADY_EXISTS);
        assertThat(live.getName()).isEqualTo("홍길동");
    }

    @Test
    void 일반_종목_주문의_생년월일은_일괄로_바꾸지_않는다() {
        Registration general = Registration.builder().id("r1").ordererKey("orderer").event(event)
                .eventCategory(mock(EventCategory.class)).name("홍길동").phNum("01011112222").birth("1990-01-01")
                .gender(GenderClass.M).build();
        orders(general);

        assertError(() -> service.modifyOrderer("orderer",
                new ParticipantOrdererModifyRequest("홍길동", "01011112222", "1991-02-02", GenderClass.M)),
                ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET);
        assertThat(general.getBirth()).isEqualTo("1990-01-01");
    }

    @Test
    void 비밀번호는_취소한_주문까지_바꾸고_refresh를_지운다() {
        Registration live = order("r1", false, "Test1234!");
        Registration canceled = order("r2", true, "Test1234!");
        orders(live, canceled);

        service.changePassword("orderer", new PersonalPasswordChangeRequest("Test1234!", "New5678!"));

        assertThat(ENCODER.matches("New5678!", live.getPassword())).isTrue();
        assertThat(ENCODER.matches("New5678!", canceled.getPassword())).isTrue();
        // 추가 주문과 같은 순서로 대회를 먼저 잠그고, 주문은 잠금 읽기로 다시 읽는다.
        verify(registrationCapacityService).lockEvent("event");
        verify(entityManager).refresh(live, LockModeType.PESSIMISTIC_WRITE);
        verify(redisService).deleteParticipantRefreshToken("orderer");
    }

    @Test
    void 트랜잭션_안에서는_커밋된_뒤에_refresh를_지운다() {
        orders(order("r1", false, "Test1234!"));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.changePassword("orderer", new PersonalPasswordChangeRequest("Test1234!", "New5678!"));
            // 커밋 전에는 지우지 않는다. 그 사이 옛 해시를 읽은 로그인이 refresh를 다시 저장하지 못하게 하려는 순서다.
            verify(redisService, never()).deleteParticipantRefreshToken(any());

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(redisService).deleteParticipantRefreshToken("orderer");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 현재_비밀번호가_틀리면_아무것도_바꾸지_않는다() {
        Registration live = order("r1", false, "Test1234!");
        orders(live);

        assertError(() -> service.changePassword("orderer", new PersonalPasswordChangeRequest("Wrong!", "New5678!")),
                ErrorCode.REGISTRATION_ACCESS_DENIED);
        assertThat(ENCODER.matches("Test1234!", live.getPassword())).isTrue();
        verify(redisService, never()).deleteParticipantRefreshToken(any());
    }

    private void orders(Registration... orders) {
        when(registrationRepository.findOrdererRegistrations("orderer")).thenReturn(List.of(orders));
        when(registrationRepository.findOrdererRegistrationsForUpdate("orderer")).thenReturn(List.of(orders));
    }

    private Registration order(String id, boolean canceled, String rawPassword) {
        return Registration.builder().id(id).ordererKey("orderer").event(event).softDeleted(canceled)
                .name("홍길동").phNum("01011112222").birth("1990-01-01").gender(GenderClass.M)
                .registrationDate(NOW.minusDays(1)).password(ENCODER.encode(rawPassword)).build();
    }

    private static void assertError(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CustomException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
