# pub.dev 배포 전 테스트 가이드

pub.dev 배포 전에 로컬에서 패키지를 검증합니다.

## 원클릭 테스트 스크립트

프로젝트 루트에서 실행:

```bash
./scripts/test-pubdev.sh
```

---

## ✅ 성공 (배포 가능)

```
========================================
✅ 테스트 통과 (path dependency 경고 있음)
========================================

코드 품질은 문제 없습니다. 배포 전 다음 작업 필요:

📦 배포 절차:

1. flowdux_flutter/pubspec.yaml 수정:
   변경 전: flowdux:
             path: ../flowdux
   변경 후: flowdux: ^0.2.1  # 현재 버전 확인

2. 배포 (순서 중요!):
   cd dart/flowdux && dart pub publish
   cd dart/flowdux_flutter && flutter pub publish

3. 배포 후 path로 복원 (개발용)
```

**핵심**: 모든 ✅ 체크 통과, path dependency 경고만 있음

---

## ❌ 실패 (배포 금지)

```
   ❌ 분석 실패 (에러 발견)
   error - lib/src/store.dart:42:5 - Undefined name 'foo'

========================================
❌ 실패: 위 오류를 수정 후 다시 테스트하세요.
========================================
```

**핵심**: `error •` 가 있으면 실패

---

## 검증 항목

| 항목 | 통과 | 실패 |
|------|------|------|
| `dart analyze` | error 없음 | error 있음 |
| `dart test` | All tests passed | 테스트 실패 |
| `dart pub publish --dry-run` | 0 warnings | error 있음 |
| path dependency 경고 | ⚠️ 정상 (개발 중) | - |

---

## 수동 테스트

### flowdux 패키지

```bash
cd dart/flowdux

dart pub get
dart analyze              # error만 확인 (info/warning 무시)
dart test
dart pub publish --dry-run
```

### flowdux_flutter 패키지

```bash
cd dart/flowdux_flutter

flutter pub get
flutter analyze           # error만 확인
flutter test
flutter pub publish --dry-run  # path dependency 경고는 정상
```

---

## 배포 순서 (중요!)

flowdux_flutter가 flowdux에 의존하므로 순서가 중요합니다:

### 1. flowdux 배포
```bash
cd dart/flowdux
# pubspec.yaml 버전 확인
dart pub publish
```

### 2. flowdux_flutter 배포 준비
```bash
cd dart/flowdux_flutter
# pubspec.yaml 수정
```

**변경 전:**
```yaml
dependencies:
  flowdux:
    path: ../flowdux
```

**변경 후:**
```yaml
dependencies:
  flowdux: ^0.2.1  # 방금 배포한 버전
```

### 3. flowdux_flutter 배포
```bash
flutter pub publish
```

### 4. 개발용으로 복원
```yaml
dependencies:
  flowdux:
    path: ../flowdux
```

---

## 버전 업데이트 체크리스트

### flowdux
- [ ] `dart/flowdux/pubspec.yaml` - version
- [ ] `dart/flowdux/CHANGELOG.md` - 변경사항

### flowdux_flutter
- [ ] `dart/flowdux_flutter/pubspec.yaml` - version
- [ ] `dart/flowdux_flutter/pubspec.yaml` - flowdux 의존성 버전
- [ ] `dart/flowdux_flutter/CHANGELOG.md` - 변경사항

### README.md
- [ ] Dart/Flutter 설치 버전 업데이트

---

## 참고

- pub.dev 배포 가이드: https://dart.dev/tools/pub/publishing
- 점수 확인: https://pub.dev/packages/flowdux/score
