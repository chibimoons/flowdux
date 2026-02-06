# Maven Central 배포 가이드

## 개요

FlowDux Kotlin 모듈은 Maven Central(Sonatype Central Portal)에 배포된다.
vanniktech/gradle-maven-publish-plugin 0.36.0 기반의 `build-logic` convention plugin으로 구성되어 있다.

- **groupId**: `io.github.chibimoons`
- **버전 관리**: `gradle.properties`의 `flowdux.version`
- **자동 배포**: GitHub Actions (`publish-maven-central.yml`)

## 배포 대상 모듈

| artifactId | 설명 |
|---|---|
| `flowdux` | Core state management |
| `flowdux-timetravel` | Time travel debugging |
| `flowdux-remote-core` | Shared action markers |
| `flowdux-remote-client` | Client middleware |
| `flowdux-remote-server` | Server middleware |
| `flowdux-remote-serialization` | kotlinx.serialization codecs |
| `flowdux-remote-ktor` | Ktor WebSocket transport |

## 배포 흐름

```
1. gradle.properties에서 flowdux.version 업데이트
2. develop → release/x.x.x → main 머지
3. git tag <version> && git push origin <version>
4. GitHub Actions 자동 실행 → Maven Central 배포
5. https://central.sonatype.com 에서 확인 (10~30분 소요)
```

## 로컬 테스트

### Maven Local에 publish

```bash
./gradlew publishToMavenLocal -x kotlinNpmInstall -x kotlinStoreYarnLock
```

### 아티팩트 확인

```bash
ls ~/.m2/repository/io/github/chibimoons/flowdux/<version>/
```

### POM 메타데이터 검증

```bash
cat ~/.m2/repository/io/github/chibimoons/flowdux/<version>/flowdux-<version>.pom
```

`<developers>`, `<scm>`, `<licenses>` 가 포함되어 있어야 한다.

### JitPack 호환성 확인

```bash
JITPACK=true ./gradlew publishToMavenLocal -x kotlinNpmInstall -x kotlinStoreYarnLock
```

JVM 아티팩트만 생성되는지 확인한다.

## CI dry-run

GitHub Actions > `Publish to Maven Central` > Run workflow > `dry_run: true`

테스트 실행 + Maven Local publish만 수행하고 실제 배포는 하지 않는다.

## GitHub Secrets

| Secret | 용도 |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Sonatype Central Portal 토큰 username |
| `MAVEN_CENTRAL_PASSWORD` | Sonatype Central Portal 토큰 password |
| `SIGNING_KEY_ID` | GPG fingerprint 마지막 8자리 |
| `SIGNING_KEY` | GPG ASCII armored private key |
| `SIGNING_KEY_PASSWORD` | GPG passphrase |

### 토큰 재발급

Central Portal > Account > Generate User Token에서 재발급 가능.
재발급 후 `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` 시크릿만 업데이트하면 된다.

### GPG 키 재생성

```bash
gpg --full-generate-key          # RSA 4096
gpg --keyserver keys.openpgp.org --send-keys <FINGERPRINT>
gpg --armor --export-secret-keys <FINGERPRINT>   # SIGNING_KEY 값
```

재생성 후 `SIGNING_KEY_ID`, `SIGNING_KEY`, `SIGNING_KEY_PASSWORD` 시크릿을 업데이트한다.

## 프로젝트 구조

```
build-logic/
├── settings.gradle.kts                          # build-logic 프로젝트 설정
├── build.gradle.kts                             # vanniktech plugin 의존성
└── src/main/kotlin/
    └── flowdux.publish-conventions.gradle.kts   # 공통 POM, 서명, Central Portal 설정

각 모듈 build.gradle.kts:
  plugins { id("flowdux.publish-conventions") }
  mavenPublishing {
      coordinates("io.github.chibimoons", "<artifactId>", ...)
      pom { name; description }
  }
```

## JitPack과의 관계

JitPack은 기존 소비자를 위해 유지된다.
`JITPACK=true` 환경에서는 JVM 타겟만 빌드하여 variant resolution 문제를 방지한다.
Maven Central은 모든 KMP 타겟(JVM, iOS, JS, Wasm)을 포함한다.
