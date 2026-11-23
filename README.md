# 캠뉴런 라이브

PC 없이 폰 하나로 치지직에 야외 방송을 하는 Android 앱. 폰 카메라·마이크를 RTMP로 치지직에 바로 보낸다.
캠뉴런(폰을 OBS 캠으로 쓰는 앱)에서 갈라져 나왔고, 이제 따로 관리한다.

**출시하지 않고 혼자 쓰는 앱이다.** 스토어에 올리지 않으며 직접 서명한 릴리즈 빌드를 내 폰(Galaxy S25 Ultra)에 깔아 쓴다.
그래서 처음 쓰는 사람을 위한 안내나 여러 기기 대응보다, 야외 방송에 실제로 필요한 것과 이 폰에서의 안정성을 먼저 본다.

## 야외 방송 기능

- 송출: 1080p/720p, 30/60fps. 폰이 뜨거우면 처리량(60→30fps, 위젯 그리기)을 줄이고, 네트워크가 약하면 비트레이트를
  낮췄다가 좋아지면 8초마다 한 단계씩 되돌린다. 폰 안에서 2초 넘게 밀린 영상은 버려 방송 지연이 쌓이지 않게 한다
  (아래 "지연 측정"). 끊기면 다시 연결한다.
- 방송 중 상태: 방송 시간, 보내는 속도, **치지직에 실제로 떴는지와 시청자 수**, 배터리·온도, **마이크 음량 막대**.
- 조작: 마이크 끄기, 화면 가리기(자리 비움), 카메라 전환·줌·초점·노출·뷰티, 플래시, 손떨림 보정.
  방송 중에 카메라를 바꿔도 연결과 영상 크기는 그대로다 (전면 카메라가 60fps를 못 내면 프레임 수만 낮춘다).
  바꾸지 못하는 폰에서는 이전 카메라로 돌아가 방송을 잇는다.
- **화면 잠금**: 터치를 막고 화면을 어둡게 한다 (주머니·짐벌, 배터리·발열). 길게 눌러 푼다.
- 방송 종료는 한 번 묻는다 (잘못 눌러 끝나지 않게).
- **작은 창(PiP)**: 방송 중에 홈 화면이나 다른 앱으로 가면 방송 화면(미리보기와 위젯)이 작은 창으로 남는다. 작은 창을
  누르면 앱으로 돌아오고, 닫아도 방송은 계속된다. 방송이 끝나면 작은 창도 닫힌다.
- 방송하지 않는 채로 화면에서 벗어난 지 10분이 지나면 앱을 스스로 끈다 (`IdleExit`. 방송 중에는 끄지 않는다).
- 치지직: 로그인하면 스트림 키를 받아 오고, 제목·카테고리·태그를 바꾸고, 채팅을 보고 쓴다.
  후원·구독은 진동과 함께 소리 내어 읽고, **채팅도 읽어 줄 수 있다** (걸으면서 방송할 때).
- 위젯: 치지직 채팅창·후원 알림, 투네이션·치지직 **후원 알림과 영상 후원** 등 OBS 브라우저 소스용 주소를 방송 화면에 얹는다.
  주소를 넣으면 알맞은 자리에 저절로 놓이고, 방송 중에도 옮기고 크기를 바꿀 수 있다.
  위젯의 소리는 폰 스피커로 나와 마이크로 들어간다 (이어폰을 끼면 시청자에게 안 들린다).
  후원이 멈췄을 때는 "위젯" → **후원 넘기기**(위젯 페이지의 시계를 빨리 감아 지금 나오는 후원만 넘긴다. 밀린 다음 후원은
  남는다)나 **새로고침**(위젯 페이지를 다시 부른다). 페이지를 불러오지 못했거나 웹 페이지를 그리는 프로세스가 죽으면 스스로 다시 부른다.

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

GitHub에서 빌드하기 (`.github/workflows/apk.yml`): main에 올라올 때나 Actions에서 직접 실행할 때 릴리즈 APK를 빌드해
Releases에 올린다. 저장소 Secrets에 `RELEASE_SIGNING`(위 두 파일을 묶은 값)이 있으면 같은 키로 서명해, 받은 APK를 깔린 앱
위에 덮어 깔 수 있다. 없으면 빌드마다 다른 임시 키로 서명한다 (덮어 깔 수 없다). 값을 만드는 법:

```
tar -cz keystore.properties keystore/camneuron-live-release.jks | base64 -w0
```

## 지연 측정

`tools/net-sim/`: 앱의 실제 송출 코드(rtmp 패키지, NetGovernor, LiveNet)를 PC에서 돌려, 느려졌다 빨라지는 망에서 영상이
가짜 서버에 얼마나 늦게 닿는지 잰다. Android SDK도 폰도 필요 없다. 카메라·인코더·치지직 서버·시청자 플레이어는 들어 있지
않으므로, 폰 안에서 밀리는 시간만 보여 준다 (치지직의 기본 지연 약 5초는 여기에 더해진다).

```
gradlew -p tools/net-sim installDist
tools/net-sim/build/install/net-sim/bin/net-sim walk out/walk.csv     (good, walk, dip, weak, flap, blackout, mixed, soak)
python3 tools/net-sim/summary.py out/*.csv
```

시청자 지연은 영상이 가장 늦게 닿았던 만큼 늘어난 뒤 잘 돌아오지 않는다 (플레이어가 멈췄다가 그 자리에서 이어 본다).
그래서 "가장 늦은 시간"을 낮게 묶는 것이 목표다. 1080p30 4.5 Mbps로 잰 값 (0.0.3. 잴 때마다 조금씩 다르다):

| 망 | 가장 늦은 시간: 고치기 전 → 지금 | 평균 비트레이트 | 버린 프레임 |
|---|---|---|---|
| 계속 좋음 (20 Mbps) | 0.02초 → 0.02초 | 4.5 → 4.5 Mbps | 0 → 0 |
| 30초 동안 2 Mbps | 15.4초 → 3.0초 | 3.0 Mbps (지금) | 125 → 127 |
| 40초 동안 1.2 Mbps (최저 비트레이트보다 느림) | 25.6초 → 3.4초 | 2.5 Mbps (지금) | 288 → 497 |
| 걷기: 45초 좋고 6초 1.5 Mbps | 4.1초 → 3.3초 | 4.5 → 3.9 Mbps | 0 → 303 |
| 자주 나빠짐 (느려짐·4초 끊김·1 Mbps 반복) | 9.5초 → 4.0초 | 4.1 → 2.1 Mbps | 174 → 903 |
| 6초 동안 완전히 끊김 | 6.0초 → 6.0초 | | 0 → 163 |

고치기 전에는 보내지 못한 영상을 소켓 버퍼(이 PC에서는 4 MB까지)에 쌓아 화질과 프레임을 지키는 대신 지연이 늘었다.
지금은 지연을 3~4초 안에 묶는 대신, 망이 나쁜 동안 비트레이트를 더 일찍 낮추고 밀린 영상을 버린다 (화면이 잠깐 멈췄다
건너뛴다). 완전히 끊긴 시간만큼 늦어지는 것은 그대로다 (소리는 버리지 않는다).

## 테스트 도구

`tools/widget-skip-test/`: 후원 넘기기 스크립트를 실제 Chromium에서 시험한다 (playwright 필요). 후원 위젯이 다음 후원으로
넘어가는 여러 방식(산 시간만큼 되풀이, 1초마다 세기, 시계 보기, 글자 알림, 다른 사이트의 플레이어, 재생이 끝나야 넘어감)을
흉내 낸 페이지로, 지금 것만 넘기고 다음 것의 시간은 거의 깎지 않는지, 넘기지 않을 때 타이머·시계가 원래대로 도는지 본다.

```
node tools/widget-skip-test/time-test.js app/src/main/assets/widget_time.js app/src/main/assets/widget_skip.js
node tools/widget-skip-test/test.js app/src/main/assets/widget_skip.js
```

`tools/rtmp-test/`: PC에서 폰 송출을 받아 보는 도구 (`adb reverse tcp:1935 tcp:1935`로 폰과 연결).

- `java Receiver.java 1935 out.flv 60`: RTMP를 받아 FLV로 저장하고 요약을 출력한다.
- `java FlvToMp4.java out.flv clip.mp4 [시작ms] [끝ms]`: 받은 영상을 MP4로 바꿔 폰·PC 플레이어로 본다.

## 지켜야 할 것

- 스트림 키, Client ID/Secret은 폰에만 저장한다. 코드·파일·로그에 넣지 않는다.
- 화면 글자는 한국어로 쓴다.
