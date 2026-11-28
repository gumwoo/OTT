# TS-0013: Windows kubectl exec -i로 보낸 덤프가 32KB 단위로 잘린다

- 상태: 해결 (원인 미확정, 전송 방식 교체)
- blog: skip
- 날짜: 발생 2026-11-28 / 해결 2026-11-28
- 모듈: deploy
- 환경: kind
- 관련: `deploy/eks/db.sh restore`, impl/07 §7-7

## 1. 증상

`db.sh restore`가 3MB 덤프에서 매번 `dump changed in transit`로 멈췄다. 처음 로컬 검증(83KB 덤프)에서는 통과했다. 전송 명령은 exit 0이었다.

```
k exec -i "$pod" -- sh -c 'cat > /work/db.dump' < "$work/db.dump"
```

| 보낸 크기 | Pod에 도착한 크기 (3회) |
|---|---|
| 3,000,000바이트 무작위 | 98,304 / 32,768 / 65,536 |

재현 방법:

1. PSS restricted namespace에 `postgres:17.11-alpine` Pod를 띄운다
2. Git Bash에서 `head -c 3000000 /dev/urandom > f.bin` 후 위 명령으로 보내고 Pod 안에서 `wc -c`

## 2. 환경

| 항목 | 값 |
|---|---|
| 버전 (프레임워크·도구) | kubectl 1.34.1(Docker Desktop 번들), kind v1.35.8 |
| 관련 설정 | Git Bash 리다이렉트로 `kubectl.exe`의 stdin에 파일을 넘김 |
| 실행 위치 | Windows 11 작업 PC → kind 노드 |

## 3. 가설

| # | 가설 | 그렇게 생각한 근거 | 확인 방법 | 결과 |
|---|---|---|---|---|
| 1 | 파일이 S3에서 잘려 왔다 | 덤프가 moto를 거친다 | 무작위 파일을 직접 보냄 | 배제 (S3 없이도 잘림) |
| 2 | WebSocket 원격 명령 프로토콜 문제 | 최근 kubectl은 기본이 WebSocket | `KUBECTL_REMOTE_COMMAND_WEBSOCKETS=false`(SPDY) | 배제 (SPDY도 잘림, 검증 에이전트) |
| 3 | Git Bash 리다이렉트가 EOF를 일찍 보낸다 | Windows 콘솔·파이프 차이 | `cat f \| kubectl exec -i`, EOF 지연 | 배제 (모두 잘림, 검증 에이전트) |
| 4 | Windows kubectl이 stdin을 다 보내기 전에 스트림을 닫는다 | 도착 크기가 늘 32KB 배수, exit 0 | Linux kubectl과 비교 | 확인 못 함 |

## 4. 원인

확정하지 못했다. 관찰한 것은 Windows kubectl의 `exec -i` stdin 전송이 32KB 단위에서 끊기고 exit 0이라는 것까지다. 반대 방향(`exec`의 stdout으로 꺼내기)은 20MB도 온전했다(검증 에이전트).

## 5. 해결

양방향 모두 `kubectl cp`로 바꿨다. SHA-256 비교는 남겼다. 이번에도 이 비교가 잘린 덤프로 복원하는 것을 막았다.

| 대안 | 장점 | 단점 | 선택 |
|---|---|---|---|
| `kubectl cp` | 3MB 3/3 온전. 추가 구성 없음 | 컨테이너에 `tar`가 있어야 한다(alpine에 있음). `C:`를 Pod 이름으로 읽어서 상대 경로로 넘겨야 한다 | 선택 |
| 덤프를 Pod가 S3에서 직접 받기 | 작업 PC를 거치지 않는다 | Pod Identity 역할과 aws CLI 이미지가 더 든다 | |
| 작게 나눠 여러 번 `exec -i` | 지금 방식 유지 | 잘림 원인을 모르니 조각 크기를 믿을 수 없다 | |

## 6. 검증

검증한 것:

- 3MB 무작위 파일: `exec -i` 3/3 잘림, `kubectl cp` 3/3 해시 일치
- 2,506,475바이트 덤프 restore 3회 성공, 행 수·내용 md5가 원본과 같다

검증하지 못한 것:

- Linux kubectl, EKS에서의 `exec -i` 동작

## 7. 재발 방지

- 테스트: `db.sh`가 Pod·작업 PC·S3 메타데이터의 SHA-256을 비교한다. 잘리면 복원 전에 멈춘다
- 규칙 (CONVENTIONS ID): 없음
- 알람·대시보드: 없음

## 8. 참고 문서

| 문서 | 링크 | 무엇을 확인했는가 |
|---|---|---|
| 없음 | | 원인을 문서로 확인하지 못했다 |

## 9. 배운 점

- exit 0만 믿지 않는다. 경계를 넘는 바이너리 전송은 양쪽 해시를 비교한다
- 작은 입력으로만 시험하면 버퍼 단위 버그가 숨는다. 실제 크기에 가까운 입력으로 한 번 더 돌린다
