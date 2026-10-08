package com.dingco.labs.w4;

/** OSIV 없이 컨트롤러가 쓸 값만 담은 조회 결과 */
public record PaymentView(Long id, long amount, String merchantName) {
}
