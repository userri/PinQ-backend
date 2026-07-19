# 🌱 경제잔디 (PinQ)

매일 아침 경제 뉴스에서 **AI가 5문제를 자동 출제**하는 경제 학습 Android 앱의 백엔드입니다.

> **지금도 매일 돌아가는 실서비스입니다.**
> 매일 06:00 KST 자동 발행 · Google Play 심사 진행 중 · AWS EC2 → Azure VM 무손실 이전 운영
> 발행분은 전수 검수해 기록합니다 → **[📋 일일 품질 검수 로그](docs/quality-audit-log.md)**

## 소개 자료

- [발표자료](https://drive.google.com/file/d/1WUFCFW81Fu84GrQl076j_p0r93rWduE2/view?usp=drive_link)
- [시연영상](https://drive.google.com/file/d/1v4g-jGRFZtFxlgvkL5EZrjUIEIaMxLtX/view?usp=sharing)

## 생성·검증 파이프라인

LLM 출력은 신뢰 경계 밖에 둡니다 — 게이트를 통과한 문항만 발행됩니다.

```
[네이버 뉴스 수집]      매일 06:00 KST · 5개 카테고리
        │
        ▼
[OpenAI 생성]           gpt-4.1-mini · 4지선다 초안
        │
        ▼
[룰베이스 검증]          인과 방향 · 길이 편향 · 절대어 오답 · 칼럼기사 차단
        │
        ▼
[교차 모델 검증]         Claude Opus · 정답 유일성 재확인
        │
        ▼
[3중 중복 방어]          프롬프트 이력 주입 + 의미 중복 + 렉시컬 유사도
        │
        ▼
[발행]                  게이트 탈락분은 폐기 → 발행분은 전수 검수 기록
```

품질은 감이 아니라 측정으로 관리합니다: 운영 문항 전수 분석 → 배포 없이 룰·모델을
갈아끼우는 A/B 실험 워크벤치로 통제 실험 → 결함 클래스 소멸을 확인한 뒤에만 채택.
실험 이력과 모델 채택 근거는 [품질 검수 로그](docs/quality-audit-log.md)의
"파이프라인 버전 이력"에 있습니다.

## 기술 스택

`Java 17` `Spring Boot 3` `MySQL 8` `Redis` `Docker` `nginx(blue/green 무중단 배포)` `GitHub Actions` `OpenAI API` `Anthropic API` `FCM`

## 더 깊은 이야기

설계 결정과 트러블슈팅 전체(CI가 배포 실패를 감춘 사건, REQUIRES_NEW 자기 교착,
타임존 이중 변환과 2차 사고, 카테고리 추가가 그날 퀴즈를 증발시킨 사고 등)는
포트폴리오 페이지에 정리되어 있습니다.

**→ [경제잔디 프로젝트 상세 보기](https://aes-portfolio-app-phi.vercel.app/project/FinQ?from=github-finq)**

## 클라이언트

- [PinQ-frontend](https://github.com/userri/PinQ-frontend) — Kotlin · Jetpack Compose (AI 보조로 제작한 프로토타입)
