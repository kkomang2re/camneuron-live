# 캠뉴런 라이브 (가칭)

PC 없이 폰 하나로 치지직에 야외 방송을 하는 Android 앱. 폰 카메라·마이크를 RTMP로 치지직에 바로 보낸다.
캠뉴런(폰을 OBS 캠으로 쓰는 앱)에서 갈라져 나왔고, 이제 따로 관리한다.

## 모듈

- `app` (`com.camneuron.live`): 화면, 방송 세션, RTMP 송출, 치지직 계정·채팅·방송 정보, 위젯.
- `core` (`com.camneuron.core`): 카메라, 영상·소리 인코더, GPU 효과(뷰티·가리기·위젯 층), 발열·네트워크 조절.
  캠뉴런의 core를 복사해 온 것이라 이제 캠뉴런과 따로 바뀐다.

## 빌드

JDK 17과 Android SDK가 필요하다 (`local.properties`의 `sdk.dir`).

```
gradlew testDebugUnitTest assembleDebug
```

디버그 APK: `app/build/outputs/apk/debug/app-debug.apk` (패키지 `com.camneuron.live.debug`).
서명 키는 아직 없다. 출시 준비 때 만들고 저장소에는 넣지 않는다.

## 테스트 도구

`tools/rtmp-test/`: PC에서 폰 송출을 받아 보는 도구 (`adb reverse tcp:1935 tcp:1935`로 폰과 연결).

- `java Receiver.java 1935 out.flv 60`: RTMP를 받아 FLV로 저장하고 요약을 출력한다.
- `java FlvToMp4.java out.flv clip.mp4 [시작ms] [끝ms]`: 받은 영상을 MP4로 바꿔 폰·PC 플레이어로 본다.

## 지켜야 할 것

- 스트림 키, Client ID/Secret은 폰에만 저장한다. 코드·파일·로그에 넣지 않는다.
- 화면 글자는 한국어로 쓴다.
