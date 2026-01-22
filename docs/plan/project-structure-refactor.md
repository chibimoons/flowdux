# 프로젝트 구조 리팩토링 계획

## 목표
Kotlin과 Dart 코드를 명확히 분리하고, 각 언어별 CI를 독립적으로 실행할 수 있도록 프로젝트 구조를 정리한다.

## 현재 구조
```
/
├── flowdux/                    # Kotlin 라이브러리
├── flowdux-timetravel/         # Kotlin 타임트래블 모듈
├── sample-android/             # Kotlin 샘플
├── sample-jvm/                 # Kotlin 샘플
├── sample-shared/              # Kotlin 샘플
├── sample-wasm/                # Kotlin 샘플
├── sample-web/                 # Kotlin 샘플
├── dart/
│   ├── flowdux/                # Dart 라이브러리
│   └── flowdux_flutter/        # Flutter 바인딩
├── gradlew, gradle/, build.gradle.kts, settings.gradle.kts
├── jitpack.yml
└── README.md, LICENSE, docs/
```

## 목표 구조
```
/
├── kotlin/
│   ├── flowdux/                # Kotlin 라이브러리
│   ├── flowdux-timetravel/     # Kotlin 타임트래블 모듈
│   ├── sample-android/         # Kotlin 샘플
│   ├── sample-jvm/             # Kotlin 샘플
│   ├── sample-shared/          # Kotlin 샘플
│   ├── sample-wasm/            # Kotlin 샘플
│   └── sample-web/             # Kotlin 샘플
├── dart/
│   ├── flowdux/                # Dart 라이브러리
│   └── flowdux_flutter/        # Flutter 바인딩
├── .github/
│   └── workflows/
│       ├── kotlin.yml          # Kotlin CI (kotlin/ 변경 시)
│       └── dart.yml            # Dart CI (dart/ 변경 시)
├── gradlew, gradle/, build.gradle.kts, settings.gradle.kts (루트 유지)
├── jitpack.yml
└── README.md, LICENSE, docs/
```

## 작업 항목

### 1. Kotlin 폴더 구조 변경
- [ ] `kotlin/` 폴더 생성
- [ ] `flowdux/` → `kotlin/flowdux/` 이동
- [ ] `flowdux-timetravel/` → `kotlin/flowdux-timetravel/` 이동
- [ ] `sample-android/` → `kotlin/sample-android/` 이동
- [ ] `sample-jvm/` → `kotlin/sample-jvm/` 이동
- [ ] `sample-shared/` → `kotlin/sample-shared/` 이동
- [ ] `sample-wasm/` → `kotlin/sample-wasm/` 이동
- [ ] `sample-web/` → `kotlin/sample-web/` 이동
- [ ] `kotlin-js-store/` → `kotlin/kotlin-js-store/` 이동

### 2. Gradle 설정 수정
- [ ] `settings.gradle.kts` - 프로젝트 경로 수정
  - `include(":flowdux")` → `include(":kotlin:flowdux")`
  - `include(":flowdux-timetravel")` → `include(":kotlin:flowdux-timetravel")`
  - 모든 sample 프로젝트 경로 수정
- [ ] 각 모듈의 `build.gradle.kts` 내부 의존성 경로 확인 및 수정
- [ ] `jitpack.yml` 경로 수정
  - `:flowdux:` → `:kotlin:flowdux:`
  - `:flowdux-timetravel:` → `:kotlin:flowdux-timetravel:`

### 3. GitHub Actions CI 설정
- [ ] `.github/workflows/kotlin.yml` 생성
  - `paths: ['kotlin/**']` 필터
  - JDK 17 설정
  - `./gradlew :kotlin:flowdux:check` 실행
- [ ] `.github/workflows/dart.yml` 생성
  - `paths: ['dart/**']` 필터
  - Dart SDK 설정
  - `dart test` 실행
  - Flutter 테스트 실행

### 4. 정리
- [ ] 불필요한 파일 제거 (있다면)
- [ ] `.gitignore` 업데이트 (필요시)
- [ ] Gradle 빌드 테스트
- [ ] Dart 테스트 실행

## 배포 영향도

### JitPack (Kotlin)
- `jitpack.yml` 경로만 수정하면 영향 없음
- 빌드 명령어: `./gradlew :kotlin:flowdux:publishToMavenLocal`

### pub.dev (Dart)
- 서브폴더에서 배포 가능
- 빌드 명령어: `cd dart/flowdux && dart pub publish`

## 검증 항목
1. `./gradlew :kotlin:flowdux:check` 성공
2. `cd dart/flowdux && dart test` 성공
3. `cd dart/flowdux_flutter && flutter test` 성공 (테스트 있는 경우)
4. GitHub Actions CI 트리거 확인
