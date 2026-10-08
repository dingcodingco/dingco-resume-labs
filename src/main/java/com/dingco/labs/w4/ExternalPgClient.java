package com.dingco.labs.w4;

import org.springframework.stereotype.Component;

/** 외부 PG 인증 호출을 200ms 지연으로 흉내 낸다. 실제 네트워크 지연과는 다르다. */
@Component
public class ExternalPgClient {

    public static final long LATENCY_MS = 200;

    public void authorize(long amount) {
        try {
            Thread.sleep(LATENCY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
