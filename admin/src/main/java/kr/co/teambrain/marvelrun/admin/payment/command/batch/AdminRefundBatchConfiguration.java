package kr.co.teambrain.marvelrun.admin.payment.command.batch;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/** 긴 동기 HTTP 요청에 JPA 엔티티가 누적되지 않도록 OSIV 비활성 설정을 필수화한다. */
@Configuration
@ConditionalOnProperty(name="admin.refund.batch.enabled", havingValue="true")
public class AdminRefundBatchConfiguration {
    /** 기본값도 허용하지 않으며 관리자 서버 설정에 false를 명시해야 한다. */
    public AdminRefundBatchConfiguration(@Value("${spring.jpa.open-in-view:true}") boolean openInView) {
        if (openInView) { throw new IllegalStateException("동기 관리자 환불은 spring.jpa.open-in-view=false가 필요합니다."); }
    }
}
