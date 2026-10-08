package com.dingco.labs.w4;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    @Query("""
            select new com.dingco.labs.w4.PaymentView(p.id, p.amount, m.name)
            from Payment p join p.merchant m
            where p.id = :id""")
    Optional<PaymentView> findView(Long id);
}
