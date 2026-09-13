package kr.co.teambrain.marvelrun.user.event.command.application.util;


import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class PaymentOrderIdGenerator {

    public String generate() {

        return "MR26_"
                + UUID.randomUUID()
                .toString()
                .replace("-", "");
    }
}