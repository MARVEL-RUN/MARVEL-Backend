package kr.co.teambrain.marvelrun.user.capacity.command.repository;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 DB에서 마지막 잔여 수량에 대한 동시 확보를 검증한다.
 *
 * 요청별로 별도 스레드와 트랜잭션을 사용한다.
 * 다른 트랜잭션에서도 보이도록 테스트 데이터를 먼저 커밋하고,
 * 모든 작업이 종료되면 테스트에서 생성한 행만 삭제한다.
 */
@Tag("capacity-db")
@DataJpaTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("capacity-test")
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CapacityConcurrencyDatabaseTest {

    @Autowired
    private CapacityCommandRepository capacityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${MARVELRUN_TEST_EVENT_ID}")
    private String eventId;

    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 9, 20, 12, 0);

    /**
     * 잔여 1명을 두 요청이 동시에 확보해도 하나만 성공하는지 검증한다.
     *
     * 시작: limit=3, held=1, confirmed=1.
     * 종료: 성공 1건, 실패 1건, held=2, confirmed=1.
     */
    @Test
    void onlyOneRequestAcquiresLastSlot() throws Exception {
        String capacityId = UUID.randomUUID().toString();

        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        /*
         * 테스트 전체를 하나의 트랜잭션으로 감싸지 않는다.
         * 먼저 데이터를 커밋해야 작업 스레드들이 해당 행을 조회할 수 있다.
         */
        transaction.executeWithoutResult(status -> {
            Integer eventCount = jdbcTemplate.queryForObject(
                    "select count(*) from event where id = ?",
                    Integer.class,
                    eventId
            );

            assertThat(eventCount)
                    .as("테스트 대상 대회가 존재해야 합니다.")
                    .isEqualTo(1);

            jdbcTemplate.update(
                    """
                    insert into capacity (
                        id, event_id, type, resource_key, name,
                        souvenir_id, size,
                        limit_count, held_count, confirmed_count,
                        active, created_at, updated_at
                    ) values (
                        ?, ?, 'CATEGORY_GROUP', ?, ?,
                        null, '',
                        3, 1, 1,
                        true, ?, ?
                    )
                    """,
                    capacityId,
                    eventId,
                    "TEST:" + capacityId,
                    "마지막 한 자리 동시 확보 검증",
                    NOW,
                    NOW
            );
        });

        /*
         * ready: 두 작업 스레드가 준비됐는지 확인한다.
         * start: 준비된 두 요청을 함께 출발시킨다.
         *
         * 대기는 트랜잭션 시작 전에 수행하여
         * 대기하는 동안 DB 연결을 점유하지 않는다.
         */
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> first = executor.submit(
                    () -> acquireInNewTransaction(
                            capacityId, ready, start
                    )
            );

            Future<Integer> second = executor.submit(
                    () -> acquireInNewTransaction(
                            capacityId, ready, start
                    )
            );

            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("두 요청이 제한 시간 안에 준비되어야 합니다.")
                    .isTrue();

            start.countDown();

            /*
             * Future는 트랜잭션의 커밋까지 끝난 뒤 결과를 반환한다.
             * 예외나 타임아웃은 정상적인 확보 실패(0)로 취급하지 않는다.
             */
            int firstResult = first.get(30, TimeUnit.SECONDS);
            int secondResult = second.get(30, TimeUnit.SECONDS);

            assertThat(new int[]{firstResult, secondResult})
                    .as("한 요청만 성공하고 다른 요청은 수량 부족으로 실패")
                    .containsExactlyInAnyOrder(1, 0);

            Integer held = jdbcTemplate.queryForObject(
                    "select held_count from capacity where id = ?",
                    Integer.class,
                    capacityId
            );

            Integer confirmed = jdbcTemplate.queryForObject(
                    "select confirmed_count from capacity where id = ?",
                    Integer.class,
                    capacityId
            );

            assertThat(held).isEqualTo(2);
            assertThat(confirmed).isEqualTo(1);
        } finally {
            /*
             * 중간 검증이 실패해도 출발 대기를 해제한다.
             * 작업 스레드 종료 후 테스트 행을 삭제한다.
             */
            start.countDown();
            executor.shutdownNow();

            boolean terminated =
                    executor.awaitTermination(60, TimeUnit.SECONDS);

            if (terminated) {
                transaction.executeWithoutResult(status ->
                        jdbcTemplate.update(
                                "delete from capacity where id = ?",
                                capacityId
                        )
                );
            }

            /*
             * 작업이 종료되지 않았다면 실행 중인 행을 임의로 삭제하지 않는다.
             * 이 경우 식별 가능한 테스트 행 ID를 실패 메시지에 남긴다.
             */
            assertThat(terminated)
                    .as(
                            "작업 스레드 종료 확인. 미종료 시 정리 대상 capacityId=%s",
                            capacityId
                    )
                    .isTrue();
        }
    }

    /**
     * 동시 출발 신호를 기다린 뒤 독립 트랜잭션에서 1명을 확보한다.
     *
     * 반환값 1은 확보 성공, 0은 Repository의 확보 조건 불충족이다.
     * 실행 중 예외는 호출자에게 전달하여 테스트를 실패시킨다.
     */
    private int acquireInNewTransaction(
            String capacityId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();

        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("동시 확보 출발 신호 대기 시간 초과");
        }

        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        transaction.setPropagationBehavior(
                Propagation.REQUIRES_NEW.value()
        );

        return transaction.execute(status ->
                capacityRepository.acquireHeld(
                        eventId,
                        capacityId,
                        1,
                        NOW
                )
        );
    }

    /**
     * 여러 자원의 확보를 같은 트랜잭션으로 처리할 때,
     * 후속 자원 부족으로 발생한 예외가 앞선 확보까지 롤백하는지 검증한다.
     *
     * 첫 자원은 잔여 1명, 두 번째 자원은 잔여 0명으로 구성한다.
     * 실패 후 두 자원의 카운터가 모두 원래 값인지 확인한다.
     */
    @Test
    void rollbackEarlierAcquireWhenNextCapacityIsFull() {
        String firstId = UUID.randomUUID().toString();
        String secondId = UUID.randomUUID().toString();

        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        /*
         * 초기 데이터를 별도 트랜잭션으로 커밋한다.
         * 확보 트랜잭션이 롤백되어도 초기 행은 남아 있어야
         * 실패 이후의 실제 카운터를 확인할 수 있다.
         */
        transaction.executeWithoutResult(status -> {
            Integer eventCount = jdbcTemplate.queryForObject(
                    "select count(*) from event where id = ?",
                    Integer.class,
                    eventId
            );

            assertThat(eventCount)
                    .as("테스트 대상 대회가 존재해야 합니다.")
                    .isEqualTo(1);

            insertRollbackTestCapacity(firstId, 1, 0);
            insertRollbackTestCapacity(secondId, 1, 1);
        });

        try {
            assertThatThrownBy(() ->
                    transaction.executeWithoutResult(status -> {
                        /*
                         * 첫 번째 확보는 성공한다.
                         * 트랜잭션 내부에서는 held=1로 증가한 상태이다.
                         */
                        assertThat(capacityRepository.acquireHeld(
                                eventId, firstId, 1, NOW
                        )).isEqualTo(1);

                        assertThat(jdbcTemplate.queryForObject(
                                "select held_count from capacity where id = ?",
                                Integer.class,
                                firstId
                        )).isEqualTo(1);

                        /*
                         * 두 번째 자원은 이미 가득 찼다.
                         * 서비스와 동일하게 확보 실패를 예외로 전환한다.
                         *
                         * UPDATE 결과 0 자체가 롤백을 발생시키는 것은 아니다.
                         */
                        int updated = capacityRepository.acquireHeld(
                                eventId, secondId, 1, NOW
                        );

                        assertThat(updated).isZero();

                        if (updated != 1) {
                            throw new CustomException(
                                    ErrorCode.CAPACITY_ACQUIRE_FAILED
                            );
                        }
                    })
            ).isInstanceOf(CustomException.class);

            /*
             * 확보 트랜잭션 종료 후 조회한다.
             * 첫 번째 증가분은 롤백되고 두 번째 수량도 그대로여야 한다.
             */
            assertThat(jdbcTemplate.queryForObject(
                    "select held_count from capacity where id = ?",
                    Integer.class,
                    firstId
            )).isZero();

            assertThat(jdbcTemplate.queryForObject(
                    "select held_count from capacity where id = ?",
                    Integer.class,
                    secondId
            )).isEqualTo(1);

            assertThat(jdbcTemplate.queryForObject(
                    """
                    select sum(confirmed_count)
                    from capacity
                    where id in (?, ?)
                    """,
                    Long.class,
                    firstId,
                    secondId
            )).isZero();
        } finally {
            // 이 테스트에서 커밋한 두 행만 정리한다.
            transaction.executeWithoutResult(status ->
                    jdbcTemplate.update(
                            "delete from capacity where id in (?, ?)",
                            firstId,
                            secondId
                    )
            );
        }
    }

    /**
     * 롤백 검증에 사용할 독립적인 Capacity 행을 생성한다.
     *
     * 호출자가 시작한 트랜잭션에 참여하며,
     * 기존 정책·매핑·Capacity 데이터는 변경하지 않는다.
     */
    private void insertRollbackTestCapacity(
            String capacityId,
            int limitCount,
            int heldCount
    ) {
        jdbcTemplate.update(
                """
                insert into capacity (
                    id, event_id, type, resource_key, name,
                    souvenir_id, size,
                    limit_count, held_count, confirmed_count,
                    active, created_at, updated_at
                ) values (
                    ?, ?, 'CATEGORY_GROUP', ?, ?,
                    null, '',
                    ?, ?, 0,
                    true, ?, ?
                )
                """,
                capacityId,
                eventId,
                "TEST:" + capacityId,
                "다중 자원 롤백 검증",
                limitCount,
                heldCount,
                NOW,
                NOW
        );
    }
}