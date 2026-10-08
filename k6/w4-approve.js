// 4주차 부록: 같은 도착률로 결제 승인 API를 호출한다.
// docker run --rm -v "$PWD/k6:/k6" grafana/k6 run -e TARGET=approve /k6/w4-approve.js
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://host.docker.internal:8080';
const TARGET = __ENV.TARGET || 'approve';

export const options = {
  scenarios: {
    approve: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 40),   // 초당 요청 수
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: 100,
      maxVUs: 400,
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const id = 1 + Math.floor(Math.random() * 1000);
  const res = http.post(`${BASE}/w4/payments/${id}/${TARGET}`, null, { timeout: '10s' });
  check(res, { 'status is 200': (r) => r.status === 200 });
}
