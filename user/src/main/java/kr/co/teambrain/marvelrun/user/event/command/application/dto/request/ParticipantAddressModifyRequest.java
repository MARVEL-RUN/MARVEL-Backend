package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import jakarta.validation.constraints.Size;

/** 마이페이지 주문 주소(배송지) 수정. 길이 제한은 개인 신청 수정과 같다. */
public record ParticipantAddressModifyRequest(
        @Size(max = 300) String address,
        @Size(max = 255) String addressDetail
) {
}
