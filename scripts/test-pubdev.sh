#!/bin/bash

# pub.dev 배포 전 테스트 스크립트
# flowdux와 flowdux_flutter 패키지를 검증합니다.

echo "========================================"
echo "pub.dev 배포 전 테스트"
echo "========================================"
echo ""

# 프로젝트 루트로 이동
cd "$(dirname "$0")/.."

FAILED=0
HAS_PATH_DEP_WARNING=0

# flowdux 테스트
echo "========================================"
echo "[1/2] flowdux 패키지 검증"
echo "========================================"
echo ""

cd dart/flowdux

echo "1. dart pub get..."
dart pub get || { echo "   ❌ pub get 실패"; FAILED=1; }
echo ""

echo "2. dart analyze..."
# info는 무시, error와 warning만 체크
ANALYZE_OUTPUT=$(dart analyze 2>&1 || true)
if echo "$ANALYZE_OUTPUT" | grep -q "error •"; then
  echo "$ANALYZE_OUTPUT" | grep "error •"
  echo "   ❌ 분석 실패 (에러 발견)"
  FAILED=1
elif echo "$ANALYZE_OUTPUT" | grep -q "No issues found"; then
  echo "   ✅ 분석 통과"
else
  # warning이나 info만 있는 경우
  echo "$ANALYZE_OUTPUT" | tail -3
  echo "   ✅ 분석 통과 (info/warning 있음, 에러 없음)"
fi
echo ""

echo "3. dart test..."
if dart test; then
  echo "   ✅ 테스트 통과"
else
  echo "   ❌ 테스트 실패"
  FAILED=1
fi
echo ""

echo "4. dart pub publish --dry-run..."
if dart pub publish --dry-run; then
  echo "   ✅ publish 검증 통과"
else
  echo "   ❌ publish 검증 실패"
  FAILED=1
fi
echo ""

cd ../..

# flowdux_flutter 테스트
echo "========================================"
echo "[2/2] flowdux_flutter 패키지 검증"
echo "========================================"
echo ""

cd dart/flowdux_flutter

echo "1. flutter pub get..."
flutter pub get || { echo "   ❌ pub get 실패"; FAILED=1; }
echo ""

echo "2. flutter analyze..."
ANALYZE_OUTPUT=$(flutter analyze 2>&1 || true)
if echo "$ANALYZE_OUTPUT" | grep -q "error •"; then
  echo "$ANALYZE_OUTPUT" | grep "error •"
  echo "   ❌ 분석 실패 (에러 발견)"
  FAILED=1
else
  echo "$ANALYZE_OUTPUT" | tail -3
  echo "   ✅ 분석 통과"
fi
echo ""

echo "3. flutter test..."
if flutter test; then
  echo "   ✅ 테스트 통과"
else
  echo "   ❌ 테스트 실패"
  FAILED=1
fi
echo ""

echo "4. flutter pub publish --dry-run..."
flutter pub publish --dry-run > /tmp/flutter-publish-check.log 2>&1 || true

if grep -q "Package has 0 warnings" /tmp/flutter-publish-check.log; then
  echo "   ✅ publish 검증 통과"
elif grep -q "path" /tmp/flutter-publish-check.log; then
  HAS_PATH_DEP_WARNING=1
  echo "   ⚠️  path dependency 경고 (배포 시 수정 필요)"
else
  cat /tmp/flutter-publish-check.log
  echo "   ❌ publish 검증 실패"
  FAILED=1
fi
echo ""

cd ../..

# 결과 출력
echo ""
echo "========================================"
if [ "$FAILED" -eq 0 ]; then
  if [ "$HAS_PATH_DEP_WARNING" -eq 1 ]; then
    echo "✅ 테스트 통과 (path dependency 경고 있음)"
    echo "========================================"
    echo ""
    echo "코드 품질은 문제 없습니다. 배포 전 다음 작업 필요:"
  else
    echo "✅ 성공: 즉시 배포 가능!"
    echo "========================================"
  fi
  echo ""
  echo "📦 배포 절차:"
  echo ""
  echo "1. flowdux_flutter/pubspec.yaml 수정:"
  echo "   변경 전: flowdux:"
  echo "             path: ../flowdux"
  echo "   변경 후: flowdux: ^X.X.X  # dart/flowdux/pubspec.yaml에서 버전 확인"
  echo ""
  echo "2. 배포 (순서 중요!):"
  echo "   cd dart/flowdux && dart pub publish"
  echo "   cd dart/flowdux_flutter && flutter pub publish"
  echo ""
  echo "3. 배포 후 path로 복원 (개발용):"
  echo "   flowdux:"
  echo "     path: ../flowdux"
  echo ""
else
  echo "❌ 실패: 위 오류를 수정 후 다시 테스트하세요."
  echo "========================================"
  exit 1
fi
