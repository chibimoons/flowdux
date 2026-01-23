#!/bin/bash

# JitPack 로컬 테스트 스크립트
# 배포 전 KMP variant resolution 문제를 미리 발견합니다.

set -e

echo "========================================"
echo "JitPack 로컬 테스트"
echo "========================================"
echo ""

# 프로젝트 루트로 이동
cd "$(dirname "$0")/.."

# 1. 기존 아티팩트 삭제
echo "[1/3] 기존 로컬 아티팩트 삭제..."
rm -rf ~/.m2/repository/io/flowdux
echo "      완료"
echo ""

# 2. JitPack 환경으로 빌드
echo "[2/3] JitPack 환경 시뮬레이션 빌드..."
echo "      (JITPACK=true 설정)"
echo ""

JITPACK=true ./gradlew :kotlin:flowdux:publishToMavenLocal \
  :kotlin:flowdux-timetravel:publishToMavenLocal \
  -x kotlinNpmInstall -x kotlinStoreYarnLock \
  -x jsTest -x wasmJsTest -x iosX64Test -x iosSimulatorArm64Test \
  --console=plain 2>&1 | tee /tmp/jitpack-test-output.log

echo ""

# 3. 결과 확인
echo "[3/3] 결과 확인..."
echo ""

# 버전 찾기
VERSION=$(ls ~/.m2/repository/io/flowdux/flowdux/ 2>/dev/null | head -1)

if [ -z "$VERSION" ]; then
  echo "❌ 실패: 아티팩트가 생성되지 않았습니다."
  exit 1
fi

echo "      버전: $VERSION"
echo ""

# .module 파일 확인
MODULE_FILE=~/.m2/repository/io/flowdux/flowdux/$VERSION/flowdux-$VERSION.module

if [ -f "$MODULE_FILE" ]; then
  echo "========================================"
  echo "❌ 실패: .module 파일이 존재합니다!"
  echo "========================================"
  echo ""
  echo "파일: $MODULE_FILE"
  echo ""
  echo "이 상태로 배포하면 JVM/Android 프로젝트에서"
  echo "variant resolution 오류가 발생합니다."
  echo ""
  echo "해결: build.gradle.kts에 다음 코드 확인:"
  echo ""
  echo "  tasks.withType<GenerateModuleMetadata> {"
  echo "      enabled = !System.getenv(\"JITPACK\").toBoolean()"
  echo "  }"
  echo ""
  exit 1
fi

# SKIPPED 확인
SKIPPED_COUNT=$(grep -c "generateMetadataFileFor.*Publication SKIPPED" /tmp/jitpack-test-output.log || echo "0")

if [ "$SKIPPED_COUNT" -eq 0 ]; then
  echo "========================================"
  echo "❌ 실패: SKIPPED 태스크를 찾을 수 없습니다!"
  echo "========================================"
  echo ""
  echo "메타데이터 생성이 실제로 SKIPPED 되었는지 검증할 수 없습니다."
  echo "빌드 로그를 확인하세요: /tmp/jitpack-test-output.log"
  echo ""
  echo "build.gradle.kts 설정을 확인한 후 다시 실행하세요:"
  echo ""
  echo "  tasks.withType<GenerateModuleMetadata> {"
  echo "      enabled = !System.getenv(\"JITPACK\").toBoolean()"
  echo "  }"
  echo ""
  exit 1
fi

echo "      SKIPPED 태스크 수: $SKIPPED_COUNT"

# 생성된 파일 목록
echo "생성된 파일:"
ls -la ~/.m2/repository/io/flowdux/flowdux/$VERSION/
echo ""

echo "========================================"
echo "✅ 성공: 배포 가능합니다!"
echo "========================================"
echo ""
echo "  - .module 파일 없음 ✓"
echo "  - POM 파일만 생성됨 ✓"
echo ""
echo "다음 단계:"
echo "  1. 버전 업데이트 (build.gradle.kts, README.md)"
echo "  2. PR 생성 및 머지"
echo "  3. 태그 생성: git tag -a X.X.X -m 'Version X.X.X'"
echo "  4. 푸시: git push origin X.X.X"
echo ""
