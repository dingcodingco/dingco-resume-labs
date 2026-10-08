package com.dingco.labs.w4;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class Merchant {

    @Id
    private Long id;
    private String name;

    protected Merchant() {
    }

    public Merchant(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
