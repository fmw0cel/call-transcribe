# 음성전사

안드로이드에서 **완전 오프라인**으로 음성을 텍스트로 바꾸는 앱입니다.
[whisper.cpp](https://github.com/ggml-org/whisper.cpp)를 NDK로 직접 빌드해 JNI로 호출하며,
오디오는 기기 밖으로 전송되지 않습니다. 인터넷은 모델을 한 번 내려받을 때만 사용합니다.

## 기능

- **녹음 → 즉시 전사**: 16 kHz 모노 PCM16 WAV로 바로 녹음해서 whisper 입력과 동일한 포맷 유지
- **파일 가져오기**: m4a, mp3, ogg, opus, flac, webm, wav(16/24/32-bit, float) → 16 kHz 모노로 디코딩
  (`MediaExtractor` + `MediaCodec`, WAV는 자체 RIFF 파서)
- **완전 로컬 추론**: whisper.cpp (v1.9.5) 를 arm64-v8a / x86_64 로 컴파일
- **모델 관리**: Tiny / Base / Small / **Large v3 Turbo** (다국어 q5) 다운로드, `.bin` 직접 가져오기, 삭제, 전환
- **실시간 미리보기**: 세그먼트가 디코딩되는 즉시 화면에 표시
- **긴 오디오 처리**: 2분 단위 청크로 나눠 전사 → 메모리 사용량 일정
- **결과**: 타임스탬프별 세그먼트, 복사, 공유, 기록 저장/삭제
- **언어 선택 / 영어 번역**: 자동 감지, 한국어, 영어, 일본어, 중국어, 스페인어, 프랑스어, 독일어, 러시아어

## 요구 사항

| 항목 | 버전 |
| --- | --- |
| Android | 8.0 (API 26)+, arm64 기기 권장 |
| JDK | 17+ (21 권장) |
| Android SDK | compileSdk 36, build-tools 36, CMake 3.22.1, NDK 27+ |
| Gradle | 8.14.3 (wrapper 포함) |

> whisper.cpp는 64-bit만 빌드합니다. 32-bit 기기는 속도/메모리 문제로 지원하지 않습니다.

## Android 기기에 설치 (다운로드)

GitHub **Releases**에서 APK를 받아 설치할 수 있습니다 (인터넷은 APK·모델 다운로드에만 사용).

1. [Releases](https://github.com/fmw0cel/call-transcribe/releases/latest) 페이지를 엽니다.
2. **Assets**에서 `app-debug.apk`를 다운로드합니다.
3. 기기에서 **출처를 알 수 없는 앱** 설치를 허용한 뒤 APK를 탭해 설치합니다.
4. 앱 실행 후 **모델 준비**에서 whisper 모델을 한 번 내려받습니다.

> CI가 올리는 디버그 APK는 Gradle 기본 디버그 키로 서명되어 있어 별도 서명 없이 sideload 할 수 있습니다.

소스에서 직접 빌드하려면 아래 절차를 사용하세요.

## 빌드

```bash
# Android SDK 경로 지정 (local.properties 또는 ANDROID_HOME)
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

# 디버그 APK
./gradlew :app:assembleDebug
# 결과: app/build/outputs/apk/debug/app-debug.apk
```

기기에 설치:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

NDK/CMake는 SDK Manager로 설치되어 있어야 합니다.

```bash
sdkmanager "ndk;27.0.12077973" "cmake;3.22.1" "platforms;android-36" "build-tools;36.0.0"
```

## 사용 방법

1. 앱을 열고 상단의 **모델 준비**를 눌러 모델을 내려받습니다.
   - 저사양: Tiny(32 MB), 일반: **Base(60 MB, 권장)**, 고사양: Small(190 MB)
   - 최고 정확도: **Turbo(574 MB)** — Large v3 Turbo, 8 GB 이상 RAM 권장 (다운로드 전 확인 창 표시)
   - 이미 `ggml-*.bin` 파일이 있다면 **직접 가져오기**로 추가할 수 있습니다.
2. 가운데 마이크를 눌러 녹음하고, 다시 눌러 중지하면 바로 전사가 시작됩니다.
3. 또는 **오디오 파일 가져오기**로 기존 녹음/통화 파일을 선택합니다.
4. 결과 화면에서 복사/공유하거나, 홈의 기록 목록에서 다시 열 수 있습니다.

모델은 `filesDir/models/`, 전사 기록은 `filesDir/transcripts/*.json` 에 저장됩니다.

## 구조

```
app/src/main/cpp/
  CMakeLists.txt          # whisper.cpp + JNI 브리지 빌드
  whisper_jni.cpp         # context 생성/해제, whisper_full, 세그먼트 콜백, 취소
app/src/main/java/com/localtranscribe/
  audio/WavRecorder.kt    # AudioRecord → WAV 스트리밍
  audio/AudioDecoder.kt   # WAV 파서 + MediaCodec 디코더 → 16 kHz WAV
  audio/MonoResampler.kt  # 4차 버터워스 저역통과 + 선형 보간 리샘플러
  audio/Wav.kt            # RIFF 헤더 읽기/쓰기
  whisper/WhisperNative.kt# JNI 선언
  whisper/WhisperLib.kt   # 모델 핸들 래퍼
  whisper/Transcriber.kt  # 2분 청크 분할, 진행률/세그먼트 집계
  model/ModelRepository.kt# 모델 카탈로그, 다운로드/가져오기, 선택 상태
  data/TranscriptStore.kt # JSON 기록 저장소
  ui/                     # Compose UI (Home / Detail / Models), ViewModel
whisper.cpp/              # vendored whisper.cpp v1.9.5 (examples/models 제외)
design/                   # 앱 아이콘 원본(icon-1024.png, icon-preview.png)과 생성 소스
```

## 구현 메모

- **포맷 통일**: 녹음과 가져오기 모두 16 kHz 모노 PCM16 WAV로 정규화한 뒤 전사합니다.
  whisper가 요구하는 입력과 정확히 일치하므로 추가 변환이 없습니다.
- **청크 전사**: 한 번에 전체 파일을 FloatArray로 올리면 1시간 오디오에서 수백 MB가 필요합니다.
  2분(1.92M 샘플, 약 7.7 MB) 단위로 읽어 `whisper_full`을 호출하고 타임스탬프를 누적합니다.
- **취소**: JNI `abort_callback`이 원자적 플래그를 확인해 디코딩 루프를 즉시 중단합니다.
- **스레드**: `availableProcessors - 1` (2~6) 로 제한. 모바일에서는 4스레드 이상에서 이득이 작습니다.
- **GPU 미사용**: whisper.cpp의 Vulkan/OpenCL 백엔드는 NDK 빌드 복잡도가 커서 CPU 추론만 사용합니다.
- **메모리**: `largeHeap=true`. Small 모델은 4 GB, Turbo 모델은 8 GB 이상 RAM 기기를 권장합니다.
  저장 공간이 부족하면 다운로드 전에 필요한 용량과 남은 용량을 안내합니다.

## 알려진 한계

- 화면이 꺼지거나 앱이 백그라운드로 오래 있으면 OS가 녹음을 중단시킬 수 있습니다.
  (포그라운드 서비스는 포함되어 있지 않습니다.)
- 2분 경계에서 단어가 잘릴 수 있습니다. 통화 녹음처럼 긴 파일은 구간 경계에서 문장이 나뉩니다.
- 화자 분리(diarization)와 실시간 스트리밍 전사는 지원하지 않습니다.
- 첫 모델 로딩과 첫 청크는 워밍업 때문에 느립니다.

## 라이선스

- 앱 코드: [MIT](LICENSE)
- `whisper.cpp/`, `ggml/`: MIT (upstream 라이선스 유지, `whisper.cpp/LICENSE`)
- 모델 가중치: OpenAI Whisper 모델 라이선스(MIT) 및 Hugging Face 배포 조건을 따릅니다.
- 전체 고지: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- 개인정보처리방침: [PRIVACY.md](PRIVACY.md)

## 개발

```bash
./gradlew :app:testDebugUnitTest   # 리샘플러 / WAV 단위 테스트
./gradlew :app:lintDebug           # Android Lint
```

`main` 브랜치에 push하거나 PR을 열면 GitHub Actions(`.github/workflows/android.yml`)가
테스트, 린트, 디버그 APK 빌드를 수행하고 APK를 아티팩트로 올립니다.

`v*` 태그(예: `v1.0.0`)를 push하면 `.github/workflows/release.yml`이
APK를 GitHub Releases에 게시합니다.
