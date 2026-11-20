# TS-0008: 인증에 실패한 Job 토큰이 401이 아니라 503으로 돌아온다

- 상태: 해결
- blog: skip
- 날짜: 발생 2026-11-20 / 해결 2026-11-20
- 모듈: api
- 환경: 로컬 kind
- 관련: 브랜치 `feat/job-token-review`
- 블로그 글: (게시되면 워크플로가 Velog 링크를 기록)

## 1. 증상

kind에 api를 올리고 `/internal/jobs/{id}/heartbeat`에 여러 토큰을 보냈다. 인증에 실패해야 하는 토큰 중 일부가 401 `UNAUTHENTICATED`가 아니라 503 `TOKEN_REVIEW_UNAVAILABLE`로 돌아왔다.

| 보낸 토큰 | 기대 | 실제 |
|---|---|---|
| 토큰 없음 | 401 | 401 |
| default SA 기본 토큰 (audience = API 서버) | 401 | **503** |
| default SA, audience `ott-api` | 401 | 401 |
| transcoder SA, audience `other-api` | 401 | **503** |
| 위조 문자열 `abc.def.ghi` | 401 | **503** |
| transcoder SA, audience `ott-api` | 인증 통과 | 404 (없는 작업 ID, 인증은 통과) |

503은 TokenReview 호출 자체가 실패했을 때 쓰는 코드다. Job은 5xx를 재시도하므로(app-design.md §8), 틀린 토큰에 503을 주면 거부가 아니라 재시도 대상이 된다.

api 로그:

```
WARN ... TokenReviewJobTokenVerifier : TokenReview request failed
org.springframework.web.client.RestClientException: Error while extracting response for type [...TokenReviewJobTokenVerifier$TokenReview]
Caused by: org.springframework.http.converter.HttpMessageNotReadableException: JSON parse error: Cannot map `null` into type `boolean` (set `DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES` to 'false' to allow)
```

## 2. 환경

| 항목 | 값 |
|---|---|
| 버전 | Spring Boot 4.1.1 (Spring Framework 7.0.9, Jackson 3), kind v0.33.0, `kindest/node:v1.35.8` |
| 관련 설정 | `TokenReviewJobTokenVerifier.Status`가 `record Status(boolean authenticated, ...)`. `RestClient.builder()`의 기본 메시지 변환기 |
| 실행 위치 | kind 클러스터 `ott`, api는 Helm 차트 `deploy/charts/ott` |

## 3. 가설

| # | 가설 | 그렇게 생각한 근거 | 확인 방법 | 결과 |
|---|---|---|---|---|
| 1 | 인증 실패 응답에서 `authenticated`가 빠져 `boolean`에 넣지 못한다 | 503이 나온 세 경우가 모두 TokenReview에서 인증 실패하는 토큰이다. 인증은 됐지만 주체가 다른 경우(default SA + `ott-api`)는 401로 정상 | 같은 토큰으로 TokenReview를 직접 만들어 응답을 본다 | 확인 |

## 4. 원인

Kubernetes는 인증에 실패하면 `status.authenticated`를 빼고 보낸다. 위조 토큰으로 직접 만든 TokenReview의 `status`:

```
{"error": "invalid bearer token", "user": {}}
```

인증에 실패하면 `authenticated` 필드 자체가 오지 않는다(실측. Kubernetes 소스의 JSON 태그는 확인하지 않았다). 받는 쪽 record는 `boolean authenticated`였고, 빠진 값은 `null`로 들어온다. Jackson 3는 `FAIL_ON_NULL_FOR_PRIMITIVES`가 기본으로 켜져 있어서 역직렬화가 실패한다. 이 예외가 "TokenReview 호출 실패"로 처리되어 503이 됐다.

단위 테스트는 실패 응답을 `{"status":{"authenticated":false, ...}}`로 직접 만들어서 이 경우를 놓쳤다.

근거:

- §1 api 로그의 `Cannot map null into type boolean`.
- `kubectl create -o json`으로 만든 TokenReview 응답 (§4 위).

## 5. 해결

`authenticated`를 `Boolean`으로 받고, `Boolean.TRUE`일 때만 인증된 것으로 본다.

```java
// api/src/main/java/dev/gumwoo/ott/common/security/TokenReviewJobTokenVerifier.java
if (status == null || !Boolean.TRUE.equals(status.authenticated())) {
...
record Status(Boolean authenticated, User user, List<String> audiences, String error) {
```

| 대안 | 장점 | 단점 | 선택 |
|---|---|---|---|
| `Boolean`으로 받기 | 이 record만 바뀐다 | 다른 필드도 omitempty일 수 있음을 기억해야 한다 | O |
| ObjectMapper에서 `FAIL_ON_NULL_FOR_PRIMITIVES` 끄기 | 다른 primitive 필드도 함께 해결 | 이 클라이언트만을 위한 ObjectMapper 설정이 필요하다 | |

## 6. 검증

검증한 것:

- `TokenReviewJobTokenVerifierTests.rejectsUnauthenticatedToken`을 kind에서 받은 실제 응답(`authenticated` 없음)으로 바꿨다. 고친 코드에서 통과, `boolean`으로 되돌리면 이 테스트만 실패.
- 이미지를 다시 빌드해 kind에 올리고 §1의 여섯 경우를 다시 보냈다. 인증에 실패하는 다섯 경우 모두 401 `UNAUTHENTICATED`, transcoder SA + `ott-api`만 통과(404).
- 이어서 ClusterRoleBinding을 지우면 503 `TOKEN_REVIEW_UNAVAILABLE`(api 로그에 403 Forbidden), helm으로 복구하면 다시 통과.

검증하지 못한 것:

- `authenticated`가 빠지는 이유(Kubernetes 소스의 JSON 태그)는 확인하지 않았다. 실측 응답만 근거로 삼았다.

## 7. 재발 방지

- 테스트: `TokenReviewJobTokenVerifierTests.rejectsUnauthenticatedToken` (실제 응답 형태).
- 규칙 (CONVENTIONS ID): 없음.
- 알람·대시보드: 해당 없음.

## 8. 참고 문서

| 문서 | 링크 | 무엇을 확인했는가 |
|---|---|---|
| kind 응답 실측 | `kubectl create -o json -f -` (§4) | 인증 실패 시 `authenticated`가 없다 |

## 9. 배운 점

- 외부 API 응답을 받는 테스트는 손으로 만든 JSON보다 실제로 받은 응답을 그대로 쓴다. false인 값이 아예 빠져서 오기도 한다.
- "호출 실패"와 "거부"를 다른 상태 코드로 나누면, 응답 해석 실패가 호출 실패 쪽으로 섞이지 않는지 따로 봐야 한다.
