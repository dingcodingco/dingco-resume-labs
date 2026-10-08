package com.dingco.labs.w4;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

import java.time.LocalDateTime;

@Entity
public class Payment {

    @Id
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Merchant merchant;

    private long amount;
    private String status;
    private LocalDateTime approvedAt;

    protected Payment() {
    }

    public Payment(Long id, Merchant merchant, long amount) {
        this.id = id;
        this.merchant = merchant;
        this.amount = amount;
        this.status = "READY";
    }

    public void approve() {
        this.status = "APPROVED";
        this.approvedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Merchant getMerchant() {
        return merchant;
    }

    public long getAmount() {
        return amount;
    }

    public String getStatus() {
        return status;
    }
}
