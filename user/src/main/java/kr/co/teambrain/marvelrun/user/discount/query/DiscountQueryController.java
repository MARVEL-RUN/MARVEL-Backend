package kr.co.teambrain.marvelrun.user.discount.query;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 공개 링크의 조건과 예상 금액을 제공한다. */
@RestController
@RequiredArgsConstructor
@Validated
public class DiscountQueryController {
    private final DiscountQueryService discounts;

    /** 코드가 있는 링크 안내만 담당하며 일반 선택지는 기존 API를 유지한다. */
    @GetMapping("/v1/public/events/{eventId}/discount-options")
    public ResponseEntity<DiscountOptionsResponse> getExternalDiscountOptions(
        @PathVariable("eventId") String eventId,
        @RequestParam("linkCode") @NotBlank @Size(max = 64) String linkCode) {
        return ResponseEntity.ok(discounts.getExternalDiscountOptions(eventId, linkCode));
    }
}
