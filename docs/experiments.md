# 실험 실행 방법

질문과 지표는 design.md §11에 있다. 여기에는 어떤 도구로 어떻게 기록하는지만 적는다. 결과 수치는 측정한 뒤 해당 ADR·README에 남기고, 측정 전 예상치는 표에 넣지 않는다.

모든 도구는 Node 24 이상, 의존성 없음. 결과는 JSONL(요청마다 한 줄)로 남긴다.

| 도구 | 실험 | 하는 일 |
|---|---|---|
| `measure/hls-probe.mjs` | E1, E2 | master → playlist → segment N개. TTFB·전체 시간·`x-cache`·POP |
| `measure/policy-probe.mjs` | E5, E6 | 같은 URL을 일정 간격으로 요청해 허용·차단(사유 코드)·오류와 엣지가 읽은 `x-policy-rev` |
| `measure/e5-report.mjs` | E5, E6 | 위치별 T2·T3·T4와 지연, 예산을 넘긴 허용(SLO 위반, E6의 False Allow) |
| `measure/api-probe.mjs` | E4, E7 | 로그인 세션으로 재생 시작 API를 순서대로 호출. 성공률·p50/p95/p99·가장 긴 실패 구간 |

## Signed Cookie

`/content/*`는 Signed Cookie가 있어야 한다. 측정 도구는 **테스트 키**(`.local/aws-test-signing.pem`)로 직접 서명하고, 테스트 키는 `/content/test-*`에서만 통한다 (design.md §5). 그래서 E1·E2·E5·E6은 `test-` 콘텐츠로 측정한다.

```bash
TEST_KEY_ID=$(terraform -chdir=infra/base output -raw test_signing_key_pair_id)
HOST=$(terraform -chdir=infra/base output -raw distribution_domain)
```

테스트 콘텐츠는 `media/seed.sh`로 content 버킷의 `content/test-<이름>/v1/`에 올리고, 정책 `policies/test-<이름>.yaml`을 둔다.

## E1·E2

```bash
node measure/hls-probe.mjs --url "https://$HOST/content/test-geo/v1/master.m3u8" \
  --key .local/aws-test-signing.pem --key-pair-id "$TEST_KEY_ID" \
  --location seoul --label warm --segments 5 --out results/e1.jsonl
```

- E2 cold는 실행마다 새 경로에 같은 산출물을 올린다: `media/seed.sh <버전 폴더> <content 버킷> content/test-e2/runs/<runId>/v1`. 엣지 함수는 `runs/{runId}/`를 `test-` 콘텐츠에만 허용한다
- 같은 runId로 두 번째부터 요청하면 warm이다. invalidation은 쓰지 않는다 (design.md §11)

## E5·E6

위치(서울·자카르타·미국 등)마다 그 지역에서 출발하는 클라이언트로 `policy-probe`를 띄워 둔 채 정책 PR을 merge한다.

```bash
node measure/policy-probe.mjs --url "https://$HOST/content/test-geo/v1/master.m3u8" \
  --key .local/aws-test-signing.pem --key-pair-id "$TEST_KEY_ID" \
  --location jakarta --interval 1000 --duration 900 --label e5-run1 --out results/e5-jakarta.jsonl
```

- T0: PR merge 시각(GitHub). T1: policy 워크플로 deploy job의 `UpdateKeys` 성공 시각(job 로그)
- 계산: `node measure/e5-report.mjs --t0 <T0> --t1 <T1> --window 60 --budget 60 results/e5-*.jsonl`
- 사유 코드 없는 403(서명·만료 실패)은 차단이 아니라 오류로 센다. 결과에는 간격·W·위치별 클라이언트 수가 함께 나온다
- T4(수렴)는 차단만 이어진 구간에서 W가 지난 시점의 차단까지 관측했을 때만 인정한다. 요청 간격의 두 배를 넘는 관측 공백이 있으면 구간을 끊는다
- SLO 위반은 T1 + `--budget`초 뒤에도 허용된 요청 수다. 예산 안의 허용은 세지 않는다. `POLICY_UNAVAILABLE`처럼 `--codes`에 없는 차단은 `otherBlocksAfterT1`로 따로 나온다
- 요청마다 10초(`--timeout`) 제한이 있고, 늦어진 요청 뒤에는 밀린 요청을 몰아 보내지 않는다
- E6(잘못된 배포)은 같은 방법으로 negative 위치(허용되면 안 되는 국가)를 돌린다. `sloViolations`가 False Allow다

## E4·E7

```bash
# 측정용 시청자 계정(콘텐츠를 볼 수 있는 구독 포함)을 웹 앱에서 만들고 .local/probe.env에 OTT_EMAIL·OTT_PASSWORD로 둔다
set -a && . .local/probe.env && set +a
node measure/api-probe.mjs --base "https://$HOST" --content coffee-run \
  --interval 500 --duration 300 --label e4-replicas2 --out results/e4.jsonl
# 다른 터미널에서 api Pod 하나만 강제 삭제한다 (E4는 "단일 Pod 장애")
pod=$(kubectl --context ott-eks -n ott get pod -l app.kubernetes.io/component=api -o name | head -1)
kubectl --context ott-eks -n ott delete "$pod" --grace-period=0 --force --wait=false
```

- 재생 시작은 `Set-Cookie`에 `CloudFront-Signature`까지 받아야 성공으로 센다
- E7은 같은 도구를 변환 Job이 돌 때와 안 돌 때로 나눠 돌리고 p95를 비교한다

## E8·E9

클러스터 사건 시각은 Kubernetes 이벤트와 노드 목록으로 남긴다. 작업 상태 전이 시각은 api DB(`transcode_jobs`)에 있다.

```bash
# -o json은 사건마다 여러 줄짜리 JSON을 이어 쓴다(JSONL이 아니다)
kubectl --context ott-eks get events -A -w -o json > results/e8-events.json &
kubectl --context ott-eks get nodes -w -o wide > results/e8-nodes.txt &
kubectl --context ott-eks -n ott port-forward svc/ott-api 8086:8080 &
uv run --project scripts/admin ott-admin --api http://localhost:8086 job <jobId> --wait
```
