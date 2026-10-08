package com.dingco.labs.w4;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/w4/payments")
public class PaymentController {

    private final PaymentService service;
    private final ExternalPgClient pg;

    public PaymentController(PaymentService service, ExternalPgClient pg) {
        this.service = service;
        this.pg = pg;
    }

    /** 기준선: 엔티티를 컨트롤러까지 들고 와서 지연 로딩한다. OSIV=true 에서만 동작한다. */
    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable Long id) {
        Payment payment = service.find(id);
        pg.authorize(payment.getAmount());
        String merchantName = payment.getMerchant().getName(); // 지연 로딩
        String status = service.approve(id);
        return Map.of("id", id, "merchant", merchantName, "status", status);
    }

    /** 개선: 필요한 값만 DTO 로 조회하고, 외부 호출은 트랜잭션 밖에서 한다. */
    @PostMapping("/{id}/approve-dto")
    public Map<String, Object> approveDto(@PathVariable Long id) {
        PaymentView view = service.findView(id);
        pg.authorize(view.amount());
        String status = service.approve(id);
        return Map.of("id", id, "merchant", view.merchantName(), "status", status);
    }

    /** 함정: 외부 호출을 @Transactional 메서드 안에 둔다. */
    @PostMapping("/{id}/approve-in-tx")
    public Map<String, Object> approveInTx(@PathVariable Long id) {
        String status = service.approveWithExternalCallInside(id);
        return Map.of("id", id, "status", status);
    }
}
