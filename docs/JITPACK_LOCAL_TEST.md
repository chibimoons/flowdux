# JitPack 로컬 테스트 가이드

JitPack 배포 전에 로컬에서 빌드를 시뮬레이션하여 KMP variant resolution 문제를 미리 발견합니다.

## 원클릭 테스트 스크립트

프로젝트 루트에서 실행:

```bash
./scripts/test-jitpack.sh
```

## 수동 테스트

### Step 1: 빌드 실행

```bash
rm -rf ~/.m2/repository/io/flowdux

JITPACK=true ./gradlew :kotlin:flowdux:publishToMavenLocal \
  :kotlin:flowdux-timetravel:publishToMavenLocal \
  -x kotlinNpmInstall -x kotlinStoreYarnLock \
  -x jsTest -x wasmJsTest -x iosX64Test -x iosSimulatorArm64Test
```

### Step 2: 결과 확인

```bash
ls ~/.m2/repository/io/flowdux/flowdux/*/
```

---

## ✅ 성공 (배포 가능)

### 빌드 로그에서 확인

```
> Task :kotlin:flowdux:generateMetadataFileForJvmPublication SKIPPED
> Task :kotlin:flowdux:generateMetadataFileForKotlinMultiplatformPublication SKIPPED
> Task :kotlin:flowdux:generateMetadataFileForJsPublication SKIPPED
...
BUILD SUCCESSFUL
```

**핵심**: `generateMetadataFileFor*Publication` 태스크들이 **SKIPPED**

### 생성된 파일 확인

```bash
$ ls ~/.m2/repository/io/flowdux/flowdux/X.Y.Z/
flowdux-X.Y.Z-kotlin-tooling-metadata.json
flowdux-X.Y.Z-sources.jar
flowdux-X.Y.Z.jar
flowdux-X.Y.Z.pom        # ✅ POM 파일만 있음
```

**핵심**: `.module` 파일이 **없음**

---

## ❌ 실패 (배포 금지)

### 빌드 로그에서 확인

```
> Task :kotlin:flowdux:generateMetadataFileForJvmPublication
> Task :kotlin:flowdux:generateMetadataFileForKotlinMultiplatformPublication
...
BUILD SUCCESSFUL
```

**문제**: `generateMetadataFileFor*Publication` 태스크가 **SKIPPED 없이 실행됨**

### 생성된 파일 확인

```bash
$ ls ~/.m2/repository/io/flowdux/flowdux/X.Y.Z/
flowdux-X.Y.Z-kotlin-tooling-metadata.json
flowdux-X.Y.Z-sources.jar
flowdux-X.Y.Z.jar
flowdux-X.Y.Z.module      # ❌ .module 파일이 있음 - 문제!
flowdux-X.Y.Z.pom
```

**문제**: `.module` 파일이 **존재함**

### 이 상태로 배포하면 발생하는 오류

JVM/Android 프로젝트에서 다음 오류 발생:

```
Could not resolve com.github.chibimoons.flowdux:flowdux-js:X.X.X.
Required by: project > com.github.chibimoons:flowdux:X.X.X
> No matching variant of com.github.chibimoons.flowdux:flowdux-js:X.X.X was found.
  The consumer was configured to find... 'org.jetbrains.kotlin.platform.type' with value 'jvm'
  but: Variant 'jsApiElements-published' declares... with value 'js'
```

---

## 실패 시 해결 방법

`kotlin/flowdux/build.gradle.kts`와 `kotlin/flowdux-timetravel/build.gradle.kts`에 다음 코드가 있는지 확인:

```kotlin
// 파일 맨 아래에 있어야 함
tasks.withType<GenerateModuleMetadata> {
    enabled = !System.getenv("JITPACK").toBoolean()
}
```

**없으면 추가하고 다시 테스트**

---

## 요약 체크리스트

| 항목 | 성공 | 실패 |
|------|------|------|
| `generateMetadataFileFor*` 태스크 | SKIPPED (1개 이상) | 실행됨 또는 찾을 수 없음 |
| `.module` 파일 | 없음 | 있음 |
| 배포 | ✅ 가능 | ❌ 금지 |

**참고**: 스크립트는 SKIPPED 태스크를 찾을 수 없으면 실패로 처리합니다. 이는 메타데이터 생성이 실제로 비활성화되었는지 검증하기 위함입니다.

---

## 참고

- JitPack은 빌드 시 자동으로 `JITPACK=true` 환경변수 설정
- `jitpack.yml`의 `install` 명령이 JitPack에서 실행됨
- JitPack 빌드 로그: `https://jitpack.io/com/github/chibimoons/flowdux/TAG/build.log`
