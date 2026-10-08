package com.dingco.labs.w4;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    private final PaymentRepository repository;
    private final ExternalPgClient pg;

    public PaymentService(PaymentRepository repository, ExternalPgClient pg) {
        this.repository = repository;
        this.pg = pg;
    }

    @Transactional(readOnly = true)
    public Payment find(Long id) {
        return repository.findById(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public PaymentView findView(Long id) {
        return repository.findView(id).orElseThrow();
    }

    @Transactional
    public String approve(Long id) {
        Payment payment = repository.findById(id).orElseThrow();
        payment.approve();
        return payment.getStatus();
    }

    /** 흔한 함정: 외부 호출을 트랜잭션 안에 둔다. OSIV 와 상관없이 커넥션을 쥔 채 기다린다. */
    @Transactional
    public String approveWithExternalCallInside(Long id) {
        Payment payment = repository.findById(id).orElseThrow();
        pg.authorize(payment.getAmount());
        payment.approve();
        return payment.getStatus();
    }
}
