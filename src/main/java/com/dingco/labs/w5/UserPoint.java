package com.dingco.labs.w5;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_point")
public class UserPoint {

    @Id
    private Long id;
    private Long userId;
    private long point;
    private LocalDateTime updatedAt;

    protected UserPoint() {
    }

    public void add(long amount, LocalDateTime now) {
        this.point += amount;
        this.updatedAt = now;
    }
}
