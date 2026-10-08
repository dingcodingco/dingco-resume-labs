# dingco-resume-labs

「6주 완성 백엔드 이력서 돋보이는 법」 2026 부록의 실습 저장소입니다.
dingco.net 프로젝트 설계도 4개를 강의 주차에 맞춰 최소 크기로 구현했고, 교재의 모든 수치는 이 저장소를 실행해 얻은 값입니다.

| 강의 주차 | 실험 | 실행 |
|---|---|---|
| 3주차 인덱스 | 통계 쿼리의 Using temporary·Using filesort 와 복합 인덱스 | `--lab=w3` |
| 4주차 트랜잭션 | OSIV·트랜잭션 범위와 HikariCP 커넥션 풀 | `./scripts/run-w4.sh` |
| 5주차 서버 최적화 | 포인트 적립 배치: Dirty Checking vs 단건 UPDATE vs JDBC batch | `--lab=w5` |
| 6주차 Redis | 주문 통계: DB GROUP BY vs Redis HINCRBY Pipeline | `--lab=w6` |

## 준비

- JDK 21, Docker
- Spring Boot 4.1.1, Gradle 9.1 (wrapper 포함)
- MySQL 8.4, Redis 7.4 (`compose.yml`), k6 1.3 (Docker 이미지)

```sh
docker compose up -d --wait
./gradlew bootJar
```

## 실행

```sh
java -jar build/libs/dingco-resume-labs-0.1.0.jar --lab=w3   # 약 수 분
./scripts/run-w4.sh                                          # 설정 5개 × k6 30초
java -jar build/libs/dingco-resume-labs-0.1.0.jar --lab=w5
java -jar build/libs/dingco-resume-labs-0.1.0.jar --lab=w6
```

결과는 `evidence/` 아래 JSON 으로 남습니다. 실행할 때마다 새 파일이 생기므로 좋은 결과 하나만 고르지 말고 전부 보관하세요.

## 읽을 때 주의할 점

- 모든 값은 **로컬 Docker 환경의 측정값**입니다. 운영 서버의 처리량이나 응답시간을 뜻하지 않습니다.
- 외부 API 지연은 `Thread.sleep(200)` 으로 흉내 냈습니다. 실제 네트워크 지연과는 분포가 다릅니다.
- 내 PC 에서 다시 실행하면 숫자는 달라집니다. 숫자보다 **같은 조건에서 어느 쪽이 어떻게 달라졌는지**를 보세요.
