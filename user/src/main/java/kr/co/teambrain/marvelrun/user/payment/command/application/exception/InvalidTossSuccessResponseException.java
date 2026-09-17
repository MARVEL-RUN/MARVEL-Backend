package kr.co.teambrain.marvelrun.user.payment.command.application.exception;

/**
 * Toss confirm API가 성공 응답을 반환했지만,
 * 응답 내용이 MarvelRun에 저장된 Payment 정보와 일치하지 않을 때 발생한다.
 *
 * 이 예외를 그대로 Controller까지 전달하면 안 된다.
 *
 * Toss에서는 이미 결제가 승인되었을 가능성이 있으므로
 * 호출자는 해당 Payment를 FAILED가 아닌 UNKNOWN 상태로 전환하고,
 * 이후 Toss 결제 조회/Reconciliation으로 실제 상태를 확인해야 한다.
 */
public class InvalidTossSuccessResponseException
        extends RuntimeException {

    public InvalidTossSuccessResponseException() {

        super(
                "Toss 결제 승인 성공 응답이 저장된 결제 정보와 일치하지 않습니다."
        );
    }
}