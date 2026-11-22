# 캠뉴런 라이브

PC 없이 폰 하나로 치지직에 야외 방송을 하는 Android 앱. 폰 카메라·마이크를 RTMP로 치지직에 바로 보낸다.
캠뉴런(폰을 OBS 캠으로 쓰는 앱)에서 갈라져 나왔고, 이제 따로 관리한다.

**출시하지 않고 혼자 쓰는 앱이다.** 스토어에 올리지 않으며 직접 서명한 릴리즈 빌드를 내 폰(Galaxy S25 Ultra)에 깔아 쓴다.
그래서 처음 쓰는 사람을 위한 안내나 여러 기기 대응보다, 야외 방송에 실제로 필요한 것과 이 폰에서의 안정성을 먼저 본다.

## 야외 방송 기능

- 송출: 1080p/720p, 30/60fps. 폰이 뜨거우면 처리량(60→30fps, 위젯 그리기)을 줄이고, 네트워크가 약하면 비트레이트를
  낮춘다. 폰 안에서 2초 넘게 밀린 영상은 버려 방송 지연이 쌓이지 않게 한다. 끊기면 다시 연결한다.
- 방송 중 상태: 방송 시간, 보내는 속도, **치지직에 실제로 떴는지와 시청자 수**, 배터리·온도, **마이크 음량 막대**.
- 조작: 마이크 끄기, 화면 가리기(자리 비움), 카메라 전환·줌·초점·노출·뷰티, 플래시, 손떨림 보정.
- **화면 잠금**: 터치를 막고 화면을 어둡게 한다 (주머니·짐벌, 배터리·발열). 길게 눌러 푼다.
- 방송 종료는 한 번 묻는다 (잘못 눌러 끝나지 않게).
- 치지직: 로그인하면 스트림 키를 받아 오고, 제목·카테고리·태그를 바꾸고, 채팅을 보고 쓴다.
  후원·구독은 진동과 함께 소리 내어 읽고, **채팅도 읽어 줄 수 있다** (걸으면서 방송할 때).
- 위젯: 치지직 채팅창·후원 알림, 투네이션·치지직 **후원 알림과 영상 후원** 등 OBS 브라우저 소스용 주소를 방송 화면에 얹는다.
  주소를 넣으면 알맞은 자리에 저절로 놓이고, 방송 중에도 옮기고 크기를 바꿀 수 있다.
  위젯의 소리는 폰 스피커로 나와 마이크로 들어간다 (이어폰을 끼면 시청자에게 안 들린다).

시청자 수는 치지직 Open API에 없어 웹 플레이어가 쓰는 공개 주소(`api.chzzk.naver.com/polling/v2/channels/{채널}/live-status`)를
읽는다. 문서에 없는 주소라 언제든 안 될 수 있고, 그때는 그 줄만 "확인 중"으로 남는다. 혼자 쓰는 앱이라 쓴다.

## 모듈

- `app` (`com.camneuron.live`): 화면, 방송 세션, RTMP 송출, 치지직 계정·채팅·방송 정보, 위젯.
- `core` (`com.camneuron.core`): 카메라, 영상·소리 인코더, GPU 효과(뷰티·가리기·위젯 층), 발열·네트워크 조절.
  캠뉴런의 core를 복사해 온 것이라 이제 캠뉴런과 따로 바뀐다.

## 빌드

JDK 17과 Android SDK가 필요하다 (`local.properties`의 `sdk.dir`).

```
gradlew testDebugUnitTest assembleDebug
```

디버그 APK: `app/build/outputs/apk/debug/app-debug.apk` (패키지 `com.camneuron.live.debug`). 시험용이다.
빌드한 PC의 디버그 키(`~/.android/debug.keystore`)로 서명되어, 다른 PC에서 빌드한 것은 덮어 깔 수 없다.

릴리즈 APK (폰에 두고 쓰는 것):

```
gradlew testReleaseUnitTest assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

`app/build/outputs/apk/release/app-release.apk` (패키지 `com.camneuron.live`). 디버그와 다른 앱이라 함께 깔리고,
설정(스트림 키, 치지직 로그인, 위젯 주소)도 따로 저장된다. 새 버전을 낼 때는 `app/build.gradle.kts`의
`versionCode`·`versionName`을 올린다.

릴리즈 서명 키는 저장소에 넣지 않는다 (`.gitignore`). 루트에 `keystore.properties`와 `keystore/camneuron-live-release.jks`가
있으면 서명하고, 없으면 서명하지 않은 APK가 나온다 (폰에 깔 수 없다). 키를 잃으면 깔린 릴리즈 앱 위에 새 버전을 덮어 깔 수
없고, 지우고 다시 깔면 폰에 저장한 설정이 사라진다. 그래서 키는 따로 보관한다.

## 테스트 도구

`tools/rtmp-test/`: PC에서 폰 송출을 받아 보는 도구 (`adb reverse tcp:1935 tcp:1935`로 폰과 연결).

- `java Receiver.java 1935 out.flv 60`: RTMP를 받아 FLV로 저장하고 요약을 출력한다.
- `java FlvToMp4.java out.flv clip.mp4 [시작ms] [끝ms]`: 받은 영상을 MP4로 바꿔 폰·PC 플레이어로 본다.

## 지켜야 할 것

- 스트림 키, Client ID/Secret은 폰에만 저장한다. 코드·파일·로그에 넣지 않는다.
- 화면 글자는 한국어로 쓴다.
