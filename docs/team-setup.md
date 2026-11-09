# 팀원 개발환경 설정 매뉴얼

> 대상: Windows PC에서 처음 저장소를 받는 팀원
> 순서대로 따라 하면 **차 앱(에뮬레이터)**, **로컬 DB**, **서버**, **폰 웹**까지 띄울 수 있습니다.

| 구분 | 버전 | 비고 |
|---|---|---|
| Android Studio | 최신 안정판 | 차 앱 |
| Pleos Connect Emulator | **2.1.0** (API 34) | 공모전 지정 |
| JDK | **21** | 서버 (차 앱은 Studio 내장 JDK 사용) |
| MySQL | **8.4** | 로컬 DB |
| Node.js | **26.x LTS** | 폰 웹 (npm 11.x 포함) |
| Git 클라이언트 | Fork 등 | |

---

## 1. 저장소 받기

1. Fork에서 **File > Clone** → `https://github.com/rladmlwlq/WAY-ON.git`
2. 받은 폴더 구조
   ```
   WAY-ON/
   ├── car-app/   Android 차량 앱 (Kotlin)
   ├── server/    Spring Boot 서버 (Java)
   ├── web/       동승자 폰 웹 (React + Vite, 5-2로 만든 뒤 생김)
   └── docs/      설계 문서(way-on.md), 앱 API(app-api.md), 외부 API(api.md), ERD, 이 매뉴얼
   ```

### 브랜치 규칙
| 브랜치 | 용도 |
|---|---|
| `main` | **작업 브랜치.** 각자 기능 브랜치를 여기서 따고, 끝나면 여기로 머지 |
| `feature/<기능>` | 개인 작업 브랜치. `main`에서 따서 `main`에 머지 |
| `develop` | **오픈 기준 브랜치.** `main`을 머지해서 반영하고, 오픈은 여기 기준. 직접 커밋하지 않음 |

---

## 2. 차 앱 (Android Studio + Pleos 에뮬레이터)

> ⚠️ 디스크 공간: Pleos 에뮬레이터 이미지는 설치 후 약 **17GB**, 설치 중에는 임시로 **약 20GB**가 더 필요합니다.
> C 드라이브가 부족하면 SDK 위치를 D 드라이브로 잡으세요 (Settings > Languages & Frameworks > Android SDK > **Android SDK Location**).

### 2-1. Pleos 에뮬레이터 이미지 설치
1. Android Studio > **Settings > Languages & Frameworks > Android SDK**
2. **SDK Update Sites** 탭 > **+** 추가
   - Name: `Pleos Connect`
   - URL: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
3. **SDK Platforms** 탭 > 오른쪽 아래 **Show Package Details** 체크
4. **Android 14.0 (API 34)** 아래 **Pleos Connect v2.1.0** 이미지 체크 > Apply
5. 설치가 끝나면 **Android Studio를 재시작**합니다 (안 하면 다음 단계 목록에 안 보입니다)

### 2-2. 가상 기기(AVD) 만들기
1. **Device Manager > Create device > Automotive** > **Pleos Connect v2.1.0** 선택
2. 시스템 이미지: API 34 **"Pleos Connect v2.1.0 APIs"**
3. Advanced: **Default boot = Cold**, **RAM 4GB**
4. Finish

### 2-3. 앱 실행
1. Studio에서 **`car-app` 폴더**를 엽니다 (저장소 루트 아님)
2. 위에 "Gradle files have changed" 배너가 뜨면 **Sync Now**
3. 기기를 Pleos AVD로 고르고 ▶ 실행
4. 차 화면에 **"WAY:ON"** 가 뜨면 완료

> `car-app/local.properties`(SDK 경로)는 Studio가 자동으로 만듭니다. git에 올라가지 않습니다.
> 휴게소 지도를 보려면 이 파일 끝에 `KAKAO_JS_KEY=`(`server/.env`의 `KAKAO_JS_KEY`와 같은 값)를 한 줄 넣고 다시 빌드합니다. 없으면 지도 없이 정보만 나옵니다.

---

## 3. 로컬 DB (MySQL 8.4)

모두 **같은 DB 이름·계정·비밀번호**를 씁니다. 그래야 서버 설정 파일을 각자 고치지 않아도 됩니다.

| 항목 | 값 |
|---|---|
| 주소 | `127.0.0.1:3306` (각자 자기 PC) |
| DB | `wayon` |
| 계정 / 비밀번호 | `wayon` / `wayon123` |

### 3-1. 설치
1. https://dev.mysql.com/downloads/mysql/ 에서 **8.4 LTS** Windows 설치
2. 포트는 기본값 **3306**, root 비밀번호는 각자 자유

### 3-2. DB·계정 만들기
root로 접속(DBeaver 또는 MySQL 콘솔)해서 실행합니다.
```sql
CREATE DATABASE wayon CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'wayon'@'localhost' IDENTIFIED BY 'wayon123';
GRANT ALL PRIVILEGES ON wayon.* TO 'wayon'@'localhost';
```

### 3-3. 테이블 만들기
`wayon` DB에서 아래 파일 내용을 실행합니다.
```
server/src/main/resources/db/schema.sql
```
테이블 16개가 생기면 완료. 구조는 `docs/erd.md` 참고.

- 로컬 DB는 각자 것이라 **지우고 다시 만들어도 됩니다.** `schema.sql`이 바뀌면 테이블을 모두 지우고 이 파일을 다시 실행합니다.
- 테이블 변경은 `schema.sql`로, 데이터 공유는 **CSV**로 git에 올립니다.

### 3-4. 휴게소 데이터 넣기
`server` 폴더에서 실행합니다. git에 있는 CSV(`server/src/main/resources/db/seed/`)를 로컬 DB에 넣고 서버가 저절로 꺼집니다. 키가 필요 없습니다.
```
gradlew bootRun --args="--wayon.seed=rest-area-load"
```
휴게소 249곳, 편의시설 1,589개, 매장 643개, 메뉴 6,939개, 판매 Top5 1,053행(휴게소 215곳), 지도 장소 1,467곳이 들어가면 완료입니다.

> 2026-11-06에 휴게소 기본키가 숫자 `id`에서 `std_rest_cd`(도로공사 코드)로 바뀌었습니다. 그 전에 휴게소 테이블을 만들었다면 `schema.sql`의 휴게소 테이블(`rest_area`, `facility`, `rest_area_brand`, `rest_area_menu`, `rest_area_top_item`, `rest_area_place`)을 지우고 다시 만든 뒤 위 명령으로 적재합니다. `rest_area_place`(휴게소 지도 장소)는 같은 날 더 생긴 테이블입니다.

도로공사 데이터를 새로 받을 때만 아래를 씁니다 (`server/.env`에 `EX_API_KEY`, `KAKAO_REST_KEY` 필요. 카카오 키는 1번 API에 없는 휴게소의 좌표를 찾는 데 씀). API에서 받아 CSV를 다시 만들고 DB에도 넣습니다. 바뀐 CSV는 커밋합니다.
```
gradlew bootRun --args="--wayon.seed=rest-area-fetch"
```
**판매순위 Top5 (매달):** 도로공사 데이터 포털에서 받은 파일(이름은 `.zip`이지만 실제로는 gzip)을 그대로 넣습니다. 같은 달은 바뀌고 다른 달은 남습니다. 바뀐 `rest_area_top_item.csv`를 커밋합니다.
```
gradlew bootRun --args="--wayon.seed=top5-import --wayon.seed.file=C:/받은/파일.zip"
```
경로에 한글이 있으면 실패할 수 있으니 영문 경로로 복사해서 넣습니다.

**휴게소 지도 장소 (가끔, 한 사람만):** 휴게소 안 매장 위치를 카카오에서 다시 받을 때 (`KAKAO_REST_KEY` 필요). 바뀐 `rest_area_place.csv`를 커밋합니다.
```
gradlew bootRun --args="--wayon.seed=rest-area-places"
```

시연 구간처럼 반대 차로 판단이 정확해야 하는 휴게소는 `rest_area_bearing_override.csv`에 방위각을 직접 적습니다 (`docs/erd.md` 휴게소 적재 규칙).

### 3-5. DBeaver 접속
- Host `127.0.0.1`, Port `3306`, Database `wayon`, 계정 `wayon` / `wayon123`
- **"Public Key Retrieval is not allowed"** 오류가 나면: 연결 편집(F4) > **Driver properties** 탭
  - `allowPublicKeyRetrieval` = `true`
  - `useSSL` = `false`

---

## 4. 서버 (Spring Boot)

### 4-1. JDK 21 준비
> ⚠️ **시스템 설정(JAVA_HOME, PATH)을 바꾸지 마세요.** 다른 프로젝트에 영향이 갑니다.
> JDK 21 설치 프로그램(MSI)은 JAVA_HOME을 자동으로 바꾸는 경우가 있으니, 아래 방법을 권장합니다.

- IntelliJ / Android Studio에서 **File > Project Structure > SDK > Download JDK** → **Amazon Corretto 21**
  - `C:\Users\<사용자>\.jdks\` 에 설치되고 시스템 설정은 그대로입니다
- 서버 빌드는 **Gradle toolchain**이 JDK 21을 자동으로 찾아 씁니다. JAVA_HOME이 17이어도 됩니다.

### 4-2. 실행
1. IntelliJ에서 **`server` 폴더**를 엽니다 (Gradle 프로젝트로 인식)
2. `ServerApplication` 실행 — 프로필을 지정하지 않으면 **local**로 뜹니다
   - 터미널: `server` 폴더에서 `gradlew bootRun`
3. 브라우저에서 http://127.0.0.1:8080/health
   ```json
   {"status":"UP","db":"UP"}
   ```
   가 나오면 서버·DB 연결 완료

### 4-3. 설정 파일 구조
```
server/src/main/resources/
├── application.properties           공통 + 프로필 그룹
├── application-local.properties     앱 설정 (로컬)
├── application-dev.properties       앱 설정 (개발)
├── application-db-local.properties  DB 설정 (로컬)
└── application-db-dev.properties    DB 설정 (개발, 공용 DB 정해지면 채움)
```
- `local`로 실행하면 `local` + `db-local`, `dev`로 실행하면 `dev` + `db-dev`가 함께 읽힙니다
- dev 실행: `gradlew bootRun --args="--spring.profiles.active=dev"`
- **설정 파일을 자기 PC에 맞게 고쳐서 커밋하지 마세요.** 다른 팀원 설정이 깨집니다. 바꿔야 하면 팀에 먼저 공유합니다.

### 4-4. 테스트
- `gradlew test` — 현재는 **로컬 MySQL이 켜져 있어야** 통과합니다

### 4-5. 외부 API 키
1. 팀에서 공유받은 키 파일을 **`server/.env`** 로 저장합니다 (파일 이름이 정확히 `.env`)
2. 들어 있는 키: `EX_API_KEY`, `DATA_GO_KR_KEY`, `OPINET_KEY`, `KAKAO_REST_KEY`, `KAKAO_JS_KEY`, `SEOUL_DATA_KEY`. 규격은 `docs/api.md`
> 서버는 시작할 때 `server` 폴더의 이 파일을 읽습니다 (`application.properties`의 `spring.config.import`).

### 4-6. 폰 웹 연동 (구현할 때 추가)
아직 서버에 없는 항목입니다. 작업하는 사람이 넣고, 이 매뉴얼에 결과를 적습니다.

| 항목 | 내용 |
|---|---|
| WebSocket | `/ws` 적용 완료 (`com.wayon.ws`). 인증·ping·`trip.start`/`trip.end`·5분 만료·`passengers.update`, 휴게소 `restarea.*` → 폰 `restarea.info`까지. 위치·경로·경유지·에너지·주차장·출발해요 메시지는 아직 |
| 허용 Origin | CORS와 WebSocket 모두 `http://localhost:5173`과 `WEB_URL`(Vercel 운영 주소, `server/.env`) 적용 완료. Origin 헤더가 없는 차량 앱은 그대로 통과 |
| 웹 푸시 키 | VAPID 키 쌍을 만들어 `server/.env`에 보관. 공개 키(`VAPID_PUBLIC_KEY`)는 서버 API(`GET /api/push/vapid-public-key`)로 웹에 전달. 없으면 503 |
| 배포 | HTTPS 도메인 필요. 배포처는 미정 |

---

## 5. 폰 웹 (React + Vite)

### 5-1. Node.js 26 준비
1. https://nodejs.org 에서 **26.x LTS** 설치
2. 터미널에서 `node -v`가 `v26`으로 시작하면 완료

> 다른 프로젝트에 다른 Node 버전이 필요하면 nvm-windows 같은 버전 관리 도구로 설치합니다.

| 패키지 | 버전 |
|---|---|
| Vite | 8.3.6 |
| React, React DOM | 19.3.1 |
| TypeScript | 6.0.3 (7은 `tsserver`가 없어 IntelliJ에서 임포트 에러가 날 수 있음) |
| `@vitejs/plugin-react` | 6.2.1 |
| `@types/react`, `@types/react-dom` | 19.3.0 |
| `@types/node` | 24.19.2 |
| oxlint | 1.91.0 |
| Kakao Maps JS SDK | npm 패키지 아님. `<script>`로 로드 (`docs/api.md`) |

정확한 버전은 `web/package-lock.json`으로 맞춥니다.

### 5-2. `web` 폴더 만들기 (처음 한 사람만)
저장소에 `web/`이 이미 있으면 건너뜁니다. 기능 브랜치에서 저장소 루트 기준으로 실행합니다.
```
npm create vite@latest web -- --template react-ts
cd web
npm install
```
1. `web/package.json`에 추가
   ```json
   "engines": { "node": "26.x" }
   ```
2. `web/vite.config.ts`의 `defineConfig`에 추가
   ```ts
   server: {
     host: true,
     proxy: {
       '/api': 'http://localhost:8080',
       '/ws': { target: 'ws://localhost:8080', ws: true },
     },
   },
   ```
3. `web/vercel.json`을 아래 내용으로 만듭니다 (새로고침해도 화면이 열리게 함)
   ```json
   { "rewrites": [{ "source": "/(.*)", "destination": "/index.html" }] }
   ```
4. `web/package-lock.json`도 함께 커밋합니다

### 5-3. 환경변수
각자 `web/.env.local`을 만들고 아래 값을 넣습니다. 이 파일은 커밋하지 않습니다.

| 변수 | 로컬 값 | Vercel 값 |
|---|---|---|
| `VITE_API_URL` | 비워 둠 (프록시 사용) | 배포한 서버 주소 |
| `VITE_KAKAO_JS_KEY` | `KAKAO_JS_KEY` 값 | 같은 값 |

> ⚠️ `VITE_`로 시작하는 값은 브라우저에 그대로 노출됩니다. 카카오 REST 키, 도로공사·오피넷 키, VAPID 개인 키를 넣지 마세요.

### 5-4. 실행
1. 서버를 먼저 띄웁니다 (4-2)
2. `web` 폴더에서 실행합니다
   ```
   npm install
   npm run dev
   ```
3. 브라우저에서 http://localhost:5173 을 엽니다

**폰으로 시험:** PC와 폰을 같은 Wi-Fi에 연결하고 `http://<PC IP>:5173`을 엽니다. 지도를 보려면 이 주소를 카카오 JS SDK 도메인에 등록해야 합니다.

**배포 전 확인:** `npm run build`

### 5-5. Vercel 배포 (수동, 모아서 한 번에)
운영 주소: https://way-on.vercel.app

GitHub와 연결하지 않고 CLI로 직접 배포합니다. 푸시해도 자동으로 배포되지 않습니다. 배포하려면 way-on 프로젝트의 Vercel 계정으로 로그인해야 합니다.

**등록된 환경변수:** `VITE_KAKAO_JS_KEY` (Production). `VITE_API_URL`은 서버를 배포한 뒤 등록합니다. 값은 Vercel 프로젝트의 **Settings > Environment Variables**에서 관리하고, 바꾼 값은 다음 배포부터 적용됩니다.

**도메인 등록:** 운영 주소는 카카오 JS SDK 도메인에 등록되어 있습니다. 서버를 만들 때 **서버 허용 Origin**(4-6)에도 등록합니다. 미리보기 주소는 바뀌니 등록하지 않습니다.

**처음 한 번만** (`web` 폴더에서)
```
npx vercel login
npx vercel link
```
`link`에서는 새 프로젝트를 만들지 말고 기존 `way-on` 프로젝트를 고릅니다.

**배포할 때마다**
1. `develop`을 최신으로 받고, 커밋하지 않은 변경이 없는지 확인합니다. CLI는 내 PC의 파일을 그대로 올립니다.
2. `web` 폴더에서 `npm run build`로 확인합니다.
3. `npx vercel --prod`로 배포합니다.
4. 운영 주소를 폰으로 열어 확인합니다.

---

## 6. 자주 막히는 곳

| 증상 | 해결 |
|---|---|
| Create device에 Pleos Connect가 안 보임 | 이미지 설치 후 **Android Studio 재시작** |
| ▶ 실행 버튼이 회색 | "Gradle files have changed" 배너의 **Sync Now** |
| 이미지 설치 실패 (공간 부족) | C 여유 공간 확보 또는 SDK 위치를 D로 변경. 실패 잔여물은 `Sdk\.temp` 폴더 |
| DBeaver "Public Key Retrieval is not allowed" | 3-5 참고 |
| `/health`가 500 에러 | MySQL이 꺼져 있음. MySQL 서비스 시작 후 재시도 |
| 서버 빌드 시 JDK 21을 못 찾음 | 4-1 방법으로 Corretto 21 설치 후 IDE 재시작 |
| "incompatible version (AGP 8.8.2) ... Latest supported version is AGP 7.4.0" | IntelliJ로 `car-app` 폴더 또는 **루트 폴더(`inCarApp`)**를 연 경우. 루트에는 Gradle 설정이 없어서 IntelliJ가 `car-app`까지 자동으로 불러옵니다. 해결: `server`는 IntelliJ, `car-app`은 **Android Studio**로 따로 열기. 루트를 꼭 IntelliJ로 열어야 하면 오른쪽 **Gradle** 창에서 `car-app` 우클릭 > **Unlink Gradle Project** (개인 `.idea` 설정이라 각자 한 번씩). AGP 버전은 내리지 않기 |
| 앱이 바로 꺼짐, Logcat에 `Waiting for debugger` 후 `ANR ... failed to complete startup` | **Debug(벌레 아이콘)**로 실행했는데 에뮬레이터가 바빠 디버거가 못 붙은 경우. **▶ Run(Shift+F10)**으로 실행. 디버거가 필요하면 Run으로 띄운 뒤 **Attach Debugger to Android Process**. 자주 그러면 AVD RAM을 8GB 이상으로 |
| 권한 요청 창이 안 보임 (화면이 검거나 그대로) | Pleos 주행 화면에 가려진 경우. adb로 직접 허용: `adb shell pm grant --user 10 com.wayon.car pleos.car.permission.FUSED_LOCATION` |
| 앱 아이콘이 두 개 보임 | 예전 앱 ID(`com.restmate.car`)로 설치한 앱이 남은 것. 아이콘 길게 눌러 삭제 또는 `adb uninstall --user 10 com.restmate.car` |
| 웹에서 카카오 지도가 안 뜸 | 지금 주소가 카카오 JS SDK 도메인에 없는 경우. 포트까지 같게 등록 (5-4, 5-5) |
| 웹에서 `/api` 호출 실패 | 서버가 꺼져 있거나, 로컬 `VITE_API_URL`에 값이 들어 있는 경우. 로컬은 비워 둠 |
| 배포한 웹에서 서버 호출·WebSocket 실패 | 서버 허용 Origin에 Vercel 운영 주소가 없는 경우 (4-6) |
| 폰에서 웹 푸시가 안 됨 | `http://<PC IP>`는 HTTPS가 아니라 동작하지 않음. Vercel 주소로 시험. iPhone은 iOS 16.4 이상에서 홈 화면에 추가해야 함 |

---

## 7. 하지 말 것
- API 키·서명 키(`*.jks`, `.env`, `.env.local`)를 새로 커밋하지 않기 (`.gitignore` 대상)
- `develop`에 직접 커밋하지 않기 (main에서 작업 → develop으로 머지)
- 개인정보가 들어간 참가신청서 작성본을 `docs/`에 올리지 않기
- 시스템 JAVA_HOME / PATH 변경하지 않기
