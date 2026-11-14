# WAY:ON

**동승자가 폰으로 고른 경로 위 휴게소·충전소·맛집을, 운전자가 차 화면에서 한 번 확인하면 Pleos 내장 내비 경유지로 들어가는 차량 앱**

현대자동차그룹 차량용 앱 개발 공모전 (Pleos Connect) 출품작입니다. 제출물은 차량 앱(APK)이고, 동승자 폰 웹과 서버가 함께 동작합니다.

## 주요 기능

| 기능 | 내용 | 쓰는 Pleos SDK |
|---|---|---|
| QR 연결·자동 재연결 | 정차 중 차 화면 QR → 동승자 폰 웹 연결. 한 번 연결한 폰은 다음 탑승부터 QR 없이 연결 | Vehicle (기어로 정차 판단) |
| 경로 위 장소 검색·경유지 반영 | 동승자가 폰 지도에서 장소를 고르면 차 화면에서 확인(그대로 두면 10초 뒤 자동 추가) → 내비 경유지 | NaviHelper |
| 에너지 제안 | 주행 가능 거리가 목적지까지 남은 거리 + 20km보다 짧으면 경로 앞쪽 충전소·주유소 제안 | Vehicle (주행 가능 거리), NaviHelper |
| 목적지 근처 공영주차장 | 목적지 10km 또는 5분 안에서 근처 공영주차장(가까운 순·싼 순) 제안 → 목적지 변경 | NaviHelper |
| 휴게소 정보 | 경유지로 넣은 휴게소에 다가가면 안내, 도착하면 차 화면에 휴게소 지도(매장 위치·영업 중·판매 Top5) | NaviHelper, Vehicle |
| 출발해요 | 휴게소에서 차 화면 [출발해요] → 동승자 폰 알림(Web Push) → 빠른 답장 대화, 기어 D면 자동 종료 | Vehicle (기어) |
| 음성 안내 | 연결된 동승자가 없을 때 에너지·주차장·휴게소 접근을 음성으로 안내 | TextToSpeech (On-Device) |

모든 화면은 정차(기어 P, 주행 규제 없음) 중에만 열리고, 주행 중에는 짧은 문구와 경유지 요청 확인 화면만 보입니다.

## 구성

```
car-app/   차량 앱 (Kotlin, Jetpack Compose, Pleos Connect SDK)
server/    서버 (Java 21, Spring Boot 4.1, MyBatis, MySQL 8.4, WebSocket)
web/       동승자 폰 웹 (React + Vite + TypeScript, 카카오맵)
apk/       제출용 릴리스 APK
docs/      설계 문서
```

## APK

- 위치: [`apk/WayOn_WayOn_release.apk`](apk/WayOn_WayOn_release.apk) (서명된 릴리스, versionName 0.1.0)
- 대상: Pleos Connect Emulator 2.1.0 (Android 14, API 34), minSdk 28
- **서버를 아직 배포하지 않아**, 이 APK는 에뮬레이터를 띄운 PC의 로컬 서버(`http://10.0.2.2:8080`)에 붙습니다. 서버 없이 실행하면 앱은 뜨지만 화면 위에 "서버 연결 끊김"이 보이고 동승자 기능은 동작하지 않습니다.

## 실행 방법

자세한 설치 순서는 [docs/team-setup.md](docs/team-setup.md)에 있습니다. 요약:

### 1. 준비
| 구분 | 버전 |
|---|---|
| Android Studio | 최신 안정판 + Pleos Connect Emulator **2.1.0** (API 34, RAM 4GB, Cold boot) |
| JDK | 21 (서버). 차 앱은 Android Studio 내장 JDK |
| MySQL | 8.4 (DB `wayon`, 계정 `wayon` / `wayon123`) |
| Node.js | 26.x (폰 웹) |

### 2. DB
```
# wayon DB에서 server/src/main/resources/db/schema.sql 실행 후 (server 폴더에서)
gradlew bootRun --args="--wayon.seed=rest-area-load"
gradlew bootRun --args="--wayon.seed=parking-load --spring.main.web-application-type=none"
```
휴게소·공영주차장 데이터는 저장소의 CSV(`server/src/main/resources/db/seed/`)에서 넣습니다. 외부 API 키가 필요 없습니다.

### 3. 서버
`server/.env`에 외부 API 키(`EX_API_KEY`, `DATA_GO_KR_KEY`, `OPINET_KEY`, `KAKAO_REST_KEY`, `KAKAO_JS_KEY`, `SEOUL_DATA_KEY`, `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`)를 넣고 `server` 폴더에서 `gradlew bootRun`. `http://127.0.0.1:8080/health`가 `{"status":"UP","db":"UP"}`이면 됩니다. 키 규격은 [docs/api.md](docs/api.md).

### 4. 폰 웹
`web/.env.local`에 `VITE_KAKAO_JS_KEY`를 넣고 `web` 폴더에서 `npm install`, `npm run dev` → `http://localhost:5173`.
실제 폰으로 시험할 때는 USB 디버깅으로 연결한 뒤 `adb reverse tcp:5173 tcp:5173`, `adb reverse tcp:8080 tcp:8080`을 하고 폰 **크롬**에서 `localhost:5173`을 엽니다 (localhost라 Web Push 알림도 시험됨).

### 5. 차량 앱
- APK 설치: `adb -s emulator-5554 install apk/WayOn_WayOn_release.apk`
- 또는 Android Studio에서 `car-app` 폴더를 열고 ▶ 실행 (디버그 빌드)
- 휴게소 지도를 보려면 `car-app/local.properties`에 `KAKAO_JS_KEY=`를 넣고 빌드합니다

## 테스트 환경과 시험 방법

- 에뮬레이터 내비에서 목적지를 정하면 차 앱이 경로를 서버로 보내고, 연결된 폰 지도에 경로가 그려집니다.
- **기어·주행 가능 거리**는 Pleos Virtual Console로 바꿉니다: `adb -s emulator-5554 forward tcp:8088 tcp:8088` 후 PC 브라우저 `http://127.0.0.1:8088/`
  - Motion → Gear (P/D): 정차 화면, 출발해요 자동 종료
  - Energy → Remaining Range (m): 에너지 제안 (예: 목적지 5km면 25000보다 작게)
- 에뮬레이터는 차 위치가 움직이지 않아 휴게소 접근 안내는 실제 주행·시연용 위치 재생이 필요합니다.
- 차 앱을 내비 안내 도중에 다시 켜면 목적지를 다시 설정해야 합니다 (내비가 남은 거리를 경로가 바뀔 때만 알려 줌).

## 릴리스 빌드 (팀 내부)

`car-app/keystore.properties`와 서명 키 파일(`car-app/wayon-release.jks`)이 있으면 `car-app` 폴더에서 `gradlew assembleRelease` → `apk/WayOn_WayOn_release.apk`로 복사됩니다. 두 파일은 git에 올리지 않습니다. 서버를 배포하면 `car-app/local.properties`에 `SERVER_URL=https://…`, `WEB_URL=https://…`를 넣고 다시 빌드합니다.

## 문서

| 문서 | 내용 |
|---|---|
| [docs/way-on.md](docs/way-on.md) | 통합 설계 (기능 범위, 결정 사항) |
| [docs/car-app-design.md](docs/car-app-design.md) | 차량 앱 설계 (화면, 흐름, SDK·권한, 에뮬레이터 확인 결과) |
| [docs/app-api.md](docs/app-api.md) | 차 ⇄ 서버 ⇄ 폰 REST·WebSocket 규격 |
| [docs/api.md](docs/api.md) | 외부 API 규격 (도로공사, 환경공단, 오피넷, 카카오, 공공 주차장) |
| [docs/erd.md](docs/erd.md) | DB 구조와 데이터 적재 규칙 |
| [docs/team-setup.md](docs/team-setup.md) | 개발 환경 설정 |
