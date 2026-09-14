package kr.co.teambrain.marvelrun.user.payment.command.application.controller;

import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.user.payment.command.application.PaymentConfirmService;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentConfirmRequest;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentConfirmResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/public/payments")
public class PaymentCommandController {

    private final PaymentConfirmService
            paymentConfirmService;


    @PostMapping("/confirm")
    public ResponseEntity<PaymentConfirmResponse> confirm(
            @Valid
            @RequestBody PaymentConfirmRequest request
    ) {

        PaymentConfirmResponse response =
                paymentConfirmService.confirm(
                        request
                );

        return ResponseEntity.ok(response);
    }
}