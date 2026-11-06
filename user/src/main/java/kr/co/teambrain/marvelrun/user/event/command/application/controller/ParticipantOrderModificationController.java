package kr.co.teambrain.marvelrun.user.event.command.application.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.user.common.security.util.ParticipantPrincipal;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.ParticipantAddressModifyRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.ParticipantOrdererModifyRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.PersonalPasswordChangeRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.service.ParticipantOrderModificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로그인한 주문자의 주문 주소·주문자 정보·비밀번호 수정. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/participant")
@PreAuthorize("hasRole('PARTICIPANT')")
public class ParticipantOrderModificationController {

    private final ParticipantOrderModificationService modifications;

    @Operation(summary = "주문 주소 수정", description = "주문 하나의 배송지(주소·상세 주소)만 바꿉니다. 신청자 정보 수정과 같은 수정 기간 안에서만 할 수 있습니다. "
            + "주소 필수 대회는 주소를 비울 수 없습니다.")
    @PatchMapping("/registrations/{registrationId}")
    public ResponseEntity<Void> modifyAddress(@PathVariable("registrationId") String registrationId,
                                              @Valid @RequestBody ParticipantAddressModifyRequest request) {
        modifications.modifyAddress(registrationId, ParticipantPrincipal.ordererKey(), request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "주문자 정보 일괄 변경", description = "이름·연락처·생년월일·성별을 이 주문자의 모든 주문(취소 포함)에 반영합니다. "
            + "살아 있는 주문 하나라도 수정 기간이 지났거나 보호자 규칙에 맞지 않으면 아무것도 바뀌지 않습니다. "
            + "바꿀 정보를 다른 주문자가 쓰고 있으면 REGISTRATION_ALREADY_EXISTS. 다음 로그인부터 새 정보로 합니다.")
    @PatchMapping("/orderer")
    public ResponseEntity<Void> modifyOrderer(@Valid @RequestBody ParticipantOrdererModifyRequest request) {
        modifications.modifyOrderer(ParticipantPrincipal.ordererKey(), request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "비밀번호 일괄 변경", description = "이 주문자의 모든 주문(취소 포함)의 비밀번호를 바꾸고 refresh 토큰을 지웁니다. "
            + "지금 쓰는 access 토큰은 만료까지 쓸 수 있고, 만료 뒤에는 새 비밀번호로 다시 로그인해야 합니다.")
    @PatchMapping("/orderer/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody PersonalPasswordChangeRequest request) {
        modifications.changePassword(ParticipantPrincipal.ordererKey(), request);
        return ResponseEntity.noContent().build();
    }
}
