# 팀원 개발환경 설정 매뉴얼

> 대상: Windows PC에서 처음 저장소를 받는 팀원
> 순서대로 따라 하면 **차 앱(에뮬레이터)**, **로컬 DB**, **서버**까지 띄울 수 있습니다.

| 구분 | 버전 | 비고 |
|---|---|---|
| Android Studio | 최신 안정판 | 차 앱 |
| Pleos Connect Emulator | **2.1.0** (API 34) | 공모전 지정 |
| JDK | **21** | 서버 (차 앱은 Studio 내장 JDK 사용) |
| MySQL | **8.4** | 로컬 DB |
| Git 클라이언트 | Fork 등 | |

---

## 1. 저장소 받기

1. Fork에서 **File > Clone** → `https://github.com/rladmlwlq/restmate.git`
2. 받은 폴더 구조
   ```
   restmate/
   ├── car-app/   Android 차량 앱 (Kotlin)
   ├── server/    Spring Boot 서버 (Java)
   └── docs/      설계 문서(design.md), ERD, 이 매뉴얼
   ```

### 브랜치 규칙
| 브랜치 | 용도 |
|---|---|
| `main` | **작업 브랜치.** 평소 작업·커밋은 여기서 |
| `develop` | **반영 브랜치.** main을 머지해서 반영. 직접 커밋하지 않음 |

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
4. 차 화면에 **"Co-Pilot Connect"** 가 뜨면 완료

> `car-app/local.properties`(SDK 경로)는 Studio가 자동으로 만듭니다. git에 올라가지 않으니 손대지 않아도 됩니다.

---

## 3. 로컬 DB (MySQL 8.4)

모두 **같은 DB 이름·계정·비밀번호**를 씁니다. 그래야 서버 설정 파일을 각자 고치지 않아도 됩니다.

| 항목 | 값 |
|---|---|
| 주소 | `127.0.0.1:3306` (각자 자기 PC) |
| DB | `copilot` |
| 계정 / 비밀번호 | `copilot` / `copilot123` |

### 3-1. 설치
1. https://dev.mysql.com/downloads/mysql/ 에서 **8.4 LTS** Windows 설치
2. 포트는 기본값 **3306**, root 비밀번호는 각자 자유

### 3-2. DB·계정 만들기
root로 접속(DBeaver 또는 MySQL 콘솔)해서 실행합니다.
```sql
CREATE DATABASE copilot CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'copilot'@'localhost' IDENTIFIED BY 'copilot123';
GRANT ALL PRIVILEGES ON copilot.* TO 'copilot'@'localhost';
```

### 3-3. 테이블 만들기
`copilot` DB에서 아래 파일 내용을 실행합니다.
```
server/src/main/resources/db/schema.sql
```
테이블 5개(`rest_area`, `facility`, `trip`, `waypoint_request`, `parking_record`)가 생기면 완료. 구조는 `docs/erd.md` 참고.

### 3-4. DBeaver 접속
- Host `127.0.0.1`, Port `3306`, Database `copilot`, 계정 `copilot` / `copilot123`
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

---

## 5. 자주 막히는 곳

| 증상 | 해결 |
|---|---|
| Create device에 Pleos Connect가 안 보임 | 이미지 설치 후 **Android Studio 재시작** |
| ▶ 실행 버튼이 회색 | "Gradle files have changed" 배너의 **Sync Now** |
| 이미지 설치 실패 (공간 부족) | C 여유 공간 확보 또는 SDK 위치를 D로 변경. 실패 잔여물은 `Sdk\.temp` 폴더 |
| DBeaver "Public Key Retrieval is not allowed" | 3-4 참고 |
| `/health`가 500 에러 | MySQL이 꺼져 있음. MySQL 서비스 시작 후 재시도 |
| 서버 빌드 시 JDK 21을 못 찾음 | 4-1 방법으로 Corretto 21 설치 후 IDE 재시작 |

---

## 6. 하지 말 것
- API 키·서명 키(`*.jks`, `.env`)를 새로 커밋하지 않기 (`.gitignore` 대상)
- `develop`에 직접 커밋하지 않기 (main에서 작업 → develop으로 머지)
- 개인정보가 들어간 참가신청서 작성본을 `docs/`에 올리지 않기
- 시스템 JAVA_HOME / PATH 변경하지 않기
