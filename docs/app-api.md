# WAY:ON 앱 API (차 ⇄ 서버 ⇄ 폰)

> 외부 API는 [api.md](api.md), 테이블은 [erd.md](erd.md), 기능 설명은 [way-on.md](way-on.md) 3장, 출발해요는 [출발해요 기획](2026-11-05-출발해요-기획.md)
> 상태: **초안 (2026-11-05)**. 11/15 MVP 기능(F1~F6, F2+, A1, A2 출발해요, N5 주차장, N6 휴게소 정보) 기준. "팀 합의 필요" 기능(N1~N4, N8, N9)은 넣지 않았습니다 (9장).

## 목차

0. 공통 규칙
1. 연결 흐름 한눈에
2. REST: 차량 앱
3. REST: 폰 웹
4. WebSocket 공통
5. WebSocket: 차량 앱 → 서버
6. WebSocket: 폰 → 서버
7. WebSocket: 서버 → 차량 앱·폰
8. 오류 코드
9. 기준값, 정하지 않은 것

---

## 0. 공통 규칙

| 항목 | 규칙 |
|---|---|
| 주소 | REST `https://{서버}/api/...`, WebSocket `wss://{서버}/ws`. 폰 웹(Vercel)과 서버 주소가 다르므로 서버에 CORS·WebSocket 허용 Origin으로 웹 주소를 등록 |
| 형식 | JSON, UTF-8. 필드 이름은 camelCase |
| 시간 | ISO-8601 UTC 문자열 (`2026-11-05T08:30:00.000Z`) |
| 좌표 | WGS84 `lat`, `lng` 숫자 (소수 6자리) |
| 거리·시간 | 거리는 **m**, 시간은 **초** (화면에 보일 때만 km·분으로 바꿈). 이름 끝에 `M`, `S`를 붙임 (`remainDistM`, `remainTimeS`) |
| ID | 세션은 `tripId`(공개 ID 32자), 그 밖은 숫자 ID. 차량 ID는 밖으로 내보내지 않음 |
| 인증 | REST는 `Authorization: Bearer {토큰}` 헤더. WebSocket은 접속 후 **첫 메시지**로 토큰을 보냄 (Pleos 보안 가이드: 토큰을 URL에 넣지 않음) |
| 토큰 | 차량 토큰(`carToken`): 차량 앱 설치 1개당 1개, 만료 없음, Android Keystore에 보관. 동승자 토큰(`passengerToken`): 연결할 때 발급, **마지막 연결 후 90일** 유효, 폰 `localStorage`에 보관. 서버는 둘 다 SHA-256 해시만 저장 |
| 성공 응답 | 본문을 바로 반환 (감싸지 않음). 본문이 없으면 `204` |
| 실패 응답 | HTTP 상태 + `{"code": "NICKNAME_TAKEN", "message": "이미 쓰는 닉네임이에요"}`. `message`는 폰·차 화면에 그대로 보여도 되는 문장. 코드는 8장 |

**서버 메소드 이름** (2026-11-06)

| 위치 | 규칙 | 예 |
|---|---|---|
| 컨트롤러·서비스 | 조회 `select_`, 추가·수정 `save_`, 삭제 `delete_`로 시작 | `select_passengers`, `save_connect`, `delete_passenger` |
| `save_` 내부 | 추가와 수정이 둘 다 있으면 `rowStat`이나 flag로 나눠 `insert_`, `update_`를 부름. 한쪽뿐이면 바로 부름 | `save_connect` → `insert_passenger` |
| 매퍼 | `select_`, `insert_`, `update_`, `delete_`로 시작 | `update_pushSubscription` |

---

## 1. 연결 흐름 한눈에

```
[처음 연결: QR]                                   [다시 연결: 자동 재연결 F5]
차량 앱 (P)                서버                폰        폰 (localStorage에 토큰 있음)      서버
 │ POST /vehicles (최초 1번) │                   │         │                                 │
 │ WS auth(carToken)         │                   │         │ WS auth(passengerToken) ───────▶│ 차량의 ACTIVE 세션에
 │ trip.start ──────────────▶│ trip 생성(ACTIVE)  │         │◀──────── auth.ok {trip}          │ trip_passenger 추가
 │ POST /connect-codes ─────▶│                   │         │                                 │ (주행 중에도 됨)
 │◀── {url} QR 표시           │                   │
 │                           │◀ QR 스캔 (url#code) │
 │                           │◀ POST /connect {code, nickname, consent}
 │                           │── {passengerToken} ▶│ localStorage 저장
 │                           │◀ WS auth(passengerToken)
 │◀── passengers.update ─────│── auth.ok {trip} ──▶│
```

- 폰 연결 순서: QR 스캔 → 닉네임 → 위치 공유 동의 → 알림 허용 → 연결 완료 (way-on.md 8.2). 알림 허용은 연결 후 버튼으로 따로 받습니다 (브라우저가 사용자 동작 뒤에만 권한을 물음).
- 자동 재연결 때는 처음 연결할 때 받은 위치 공유 동의를 그대로 씁니다. `trip_passenger.consented_at`에는 세션에 붙은 시각을 넣습니다.
- 세션(`trip`)은 **차량 앱이 연다.** 폰은 그 차량의 ACTIVE 세션에 붙기만 합니다. ACTIVE 세션이 없으면 `auth.ok`의 `trip`이 `null`이고, 폰은 "차량 앱이 켜지면 자동으로 연결돼요"를 보여 줍니다. 세션이 열리면 서버가 `trip.started`를 보냅니다.

---

## 2. REST: 차량 앱

### 2-1. `POST /api/vehicles` — 차량 등록

앱을 처음 실행했을 때 1번. 인증 없음.

Request: 본문 없음

Response `201`
```json
{"carToken": "c_8Jx…(43자)", "createdAt": "2026-11-05T08:00:00.000Z"}
```
- `carToken`은 이 응답에서만 받을 수 있습니다. 잃어버리면 새로 등록합니다 (등록 동승자는 다시 연결해야 함).

### 2-2. `POST /api/vehicles/me/connect-codes` — QR 코드 발급

정차(P) 중에만 호출합니다. **기어 판단은 차량 앱이 합니다** (서버는 기어를 모름).

Request
```json
{"kind": "QR"}
```

Response `201`
```json
{"code": "k3Qm…(22자)", "url": "https://{웹}/join#k3Qm…", "expiresAt": "2026-11-05T08:02:00.000Z"}
```
- 만료 3분. **만료 전까지 QR 하나로 여러 명이 연결**합니다 (2026-11-09 결정, 일회용에서 바꿈). 새로 발급하면 그 차량의 이전 코드는 폐기합니다.
- 늦게 온 사람은 차량 앱이 QR을 다시 띄워 연결합니다. 이미 연결한 사람은 토큰으로 자동 재연결되므로 영향 없습니다. 주행 중 합류는 [backlog-late-join.md](backlog-late-join.md).
- 등록 동승자가 4명이면 `409 PASSENGER_LIMIT`.

### 2-3. `GET /api/vehicles/me/passengers` — 등록 동승자 목록

Response `200`
```json
{"passengers": [
  {"passengerId": 12, "nickname": "민지", "online": true, "pushEnabled": true,
   "lastConnectedAt": "2026-11-05T08:01:10.000Z"}
]}
```
- `revoked_at`이 없는 동승자만. `online` = 지금 WebSocket이 붙어 있음.

### 2-4. `DELETE /api/vehicles/me/passengers/{passengerId}` — 동승자 해제

Response `204`. 그 동승자의 WebSocket은 `4403`으로 닫습니다. 대화 기록은 남기고, 닉네임은 "(나간 동승자)"로 보여 줍니다.

### 2-5. `GET /api/vehicles/me/departure-messages?before={messageId}&size=30` — 지난 대화

차량 앱·폰 공용 (폰은 동승자 토큰으로, 자기 차량의 대화). 출발해요 기획 9-2.

Response `200` (최신이 마지막)
```json
{"messages": [
  {"messageId": 301, "callId": 40,
   "sender": {"passengerId": 12, "nickname": "민지"},
   "kind": "QUICK", "quickCode": "TOILET", "body": null,
   "sentAt": "2026-11-05T08:10:00.000Z"},
  {"messageId": 302, "callId": 40,
   "sender": {"passengerId": null, "nickname": "운전자"},
   "kind": "TEXT", "quickCode": null, "body": "주차장 B동이에요",
   "sentAt": "2026-11-05T08:10:30.000Z"}
 ],
 "hasMore": true}
```
- `before`가 없으면 최신 것부터. 위로 올리면 가장 오래된 `messageId`를 `before`로 다시 부릅니다. `size` 최대 50.
- 보낸 지 30일 지난 메시지는 없습니다 (하루 1번 삭제).

---

## 3. REST: 폰 웹

### 3-1. `POST /api/connect` — QR로 연결

인증 없음 (코드가 인증).

Request
```json
{"code": "k3Qm…", "nickname": "민지", "locationConsent": true}
```

Response `201`
```json
{"passengerToken": "p_Zt…(43자)", "passengerId": 12, "nickname": "민지",
 "tokenExpiresAt": "2027-02-03T08:01:10.000Z"}
```

| 실패 | 상태 | 코드 |
|---|---|---|
| 코드가 없음 (새 QR이 발급돼 폐기된 것 포함) | 404 | `CODE_INVALID` |
| 코드 만료 | 410 | `CODE_EXPIRED` |
| 닉네임 1~10자 아님, 공백만 | 400 | `NICKNAME_INVALID` |
| 같은 차에 같은 닉네임 (해제된 동승자는 제외) | 409 | `NICKNAME_TAKEN` |
| 등록 동승자 4명 | 409 | `PASSENGER_LIMIT` |
| `locationConsent`가 `true`가 아님 | 400 | `CONSENT_REQUIRED` |

- 닉네임은 앞뒤 공백을 지우고 비교합니다. 중복 확인은 `vehicle` 행을 `FOR UPDATE`로 잠그고 합니다 (erd.md).
- 이미 토큰이 있는 폰이 다른 차의 QR을 찍으면 새 동승자로 등록하고 토큰을 바꿉니다. 한 폰은 차 1대만 기억합니다.

### 3-2. `GET /api/passengers/me` — 토큰 확인

폰을 다시 열었을 때 토큰이 살아 있는지 확인합니다.

Response `200`
```json
{"passengerId": 12, "nickname": "민지", "pushEnabled": false,
 "tokenExpiresAt": "2027-02-03T08:01:10.000Z"}
```
- 토큰이 없거나 만료·해제면 `401 TOKEN_INVALID` → 폰은 토큰을 지우고 "QR을 다시 찍어 주세요".
- 유효 기간 90일은 **연결할 때마다**(WebSocket `auth` 성공) 다시 셉니다.

### 3-3. `GET /api/push/vapid-public-key` — Web Push 공개 키

인증 없음. Response `200` `{"publicKey": "BEl6…"}` (URL-safe Base64). 개인 키는 서버 환경변수에만.

### 3-4. `PUT /api/passengers/me/push-subscription` — 알림 구독 저장

Request (브라우저 `PushSubscription.toJSON()` 그대로)
```json
{"endpoint": "https://fcm.googleapis.com/fcm/send/…",
 "keys": {"p256dh": "BN…", "auth": "tB…"}}
```
Response `204`. 같은 폰이 다시 보내면 덮어씁니다.

### 3-5. `DELETE /api/passengers/me/push-subscription` — 알림 끄기

Response `204`. 네 칸을 NULL로.

### 3-6. `GET /api/trips/current/places?type={type}` — 경로 위 장소 (F2, F2+)

동승자 토큰. 칩을 누를 때 부릅니다. 경로·차 위치는 서버가 이미 알고 있습니다 (5장 `car.location`, `car.route`).

| 파라미터 | 값 |
|---|---|
| `type` | `REST_AREA` 휴게소 / `EV_CHARGER` 충전소 / `GAS_STATION` 주유소 / `RESTAURANT` 맛집 |
| `limit` | 기본 20, 최대 50 |

Response `200` (차에서 가까운 순)
```json
{"routeVersion": 3,
 "places": [
  {"type": "RESTAURANT", "extId": "kakao:1234567", "name": "할매국밥",
   "address": "충북 청주시 …", "lat": 36.62, "lng": 127.43,
   "aheadM": 18200, "etaS": 900, "offRouteM": 400,
   "detail": {"category": "한식 > 국밥", "phone": "043-…", "placeUrl": "http://place.map.kakao.com/1234567"}}
 ]}
```

`detail`은 종류마다 다릅니다.

| type | `extId` | `detail` |
|---|---|---|
| `REST_AREA` | `ra:{std_rest_cd}` (`ra:000001`) | `{"routeName": "경부선", "direction": "서울방향", "top1": "말죽거리소고기국밥", "facilities": ["04", "12"], "hasGasStation": true}` |
| `EV_CHARGER` | `ev:{stat_id}` | `{"operator": "환경부", "available": 2, "total": 4, "maxKw": 100, "accessLimited": false}` |
| `GAS_STATION` | `opinet:{UNI_ID}` | `{"brand": "SKE", "gasoline": 1689, "diesel": 1559, "lpg": null}` (원/L) |
| `RESTAURANT` (일반도로) | `kakao:{id}` | `{"category": "…", "phone": "…", "placeUrl": "…"}` |
| `RESTAURANT` (고속도로) | `ra:{std_rest_cd}` | `{"restAreaName": "경산(서울)휴게소", "topItems": [{"rank": 1, "name": "말죽거리소고기국밥", "price": 9000}]}` 휴게소 1곳이 1행 |

- `RESTAURANT`는 **경로를 바꾸지 않고 들를 수 있는 곳만** 줍니다 (way-on.md 8.1 맛집 범위). 고속도로 구간은 앞쪽 휴게소의 판매 Top5(`ra:` 행, 우리 DB), 일반도로 구간은 **경로선 200m 안**의 카카오 식당(`kakao:` 행). 고속도로 구간은 길찾기 응답의 도로 이름으로 나눕니다 (api.md 11).
- `offRouteM`: 경로선에서 떨어진 거리. 휴게소 행은 0.
- 세션이 없으면 `404 TRIP_NOT_ACTIVE`, 목적지가 없어 경로가 없으면 `409 NO_ROUTE` → 폰은 "목적지를 정하면 경로 위에서 찾아 드려요".
- 에너지 제안(F6) 중이면 `GAS_STATION`·`EV_CHARGER`는 **주행 가능 거리 안**에 있는 것만 줍니다.

### 3-7. `GET /api/rest-areas/{restAreaId}` — 휴게소 상세 (N6, A1)

`restAreaId`는 도로공사 휴게소 코드 6자리(`std_rest_cd`, 예: `000001`). 장소 `extId`의 `ra:` 뒤 값과 같습니다. 다시 적재해도 바뀌지 않습니다.

동승자 토큰.

Response `200`
```json
{"restAreaId": "000048", "name": "경산(서울)휴게소", "routeName": "경부선", "direction": "서울방향",
 "lat": 35.86, "lng": 128.80,
 "topItems": [
   {"rank": 1, "name": "말죽거리소고기국밥", "store": "말죽거리한식당", "price": 9000},
   {"rank": 2, "name": "옛날돈가스", "store": "돈가스잔치", "price": null}],
 "topMonth": "2026-10",
 "brands": [{"name": "할리스", "open": "07:00", "close": "22:00", "openNow": true}],
 "facilities": [
   {"code": "04", "name": "수유실", "detail": null, "open": "00:00", "close": "24:00", "source": "REST_AREA"},
   {"code": "99", "name": "기타", "detail": "수소충전소 운영 …", "open": "09:00", "close": "18:00", "source": "GAS_STATION"}]}
```
- `openNow`는 서버가 한국 시간으로 계산합니다. `close`가 `open`보다 이르면 다음 날까지로 봅니다.
- `price`는 0502 메뉴와 이름이 맞을 때만 있습니다 (erd.md 휴게소 적재 규칙).
- 없는 코드거나 6자리 숫자가 아니면 `404 PLACE_NOT_FOUND`.

### 3-8. `GET /api/health`

인증 없음. `{"status": "UP", "db": "UP"}` (지금 있는 `HealthController`).

### 3-9. `GET /api/rest-areas/{restAreaId}/map` — 휴게소 지도 (A1, 차 화면 4-7)

차량 토큰 (인증 공통부가 생기면 붙임, 지금은 열려 있음). 휴게소에 도착하면 차량 앱이 한 번 부릅니다.

Response `200`
```json
{"restAreaId": "000555", "name": "매송(목포)휴게소", "direction": "목포방향", "lat": 37.265122, "lng": 126.888717,
 "topMonth": "2026-10",
 "places": [
   {"placeId": "kakao:1234", "name": "할리스 매송휴게소목포방향점", "kind": "CAFE", "lat": 37.2647, "lng": 126.8879,
    "open": "08:00", "close": "20:00", "openNow": true, "topItems": []},
   {"placeId": "kakao:5678", "name": "매송주유소 목포방향", "kind": "GAS", "lat": 37.2636, "lng": 126.8885,
    "open": null, "close": null, "openNow": null, "topItems": []}],
 "building": {
   "foodCourt": {
     "topItems": [{"rank": 2, "name": "뚝배기제육", "store": "양갑조국밥", "placeId": null, "price": 10500}],
     "menus": [{"name": "곰탕", "store": "유가네", "placeId": null, "price": 10000, "best": true, "recommended": false, "premium": false}]},
   "otherStores": [{"name": "드롭탑(커피전문점)", "open": "08:00", "close": "20:00", "openNow": true,
                    "topItems": [{"rank": 5, "name": "DT_ICE아메리카노(L)", "store": "카페드롭탑", "price": null}]}],
   "facilities": [{"code": "04", "name": "수유실", "detail": null, "open": "00:00", "close": "24:00", "source": "REST_AREA"}]}}
```
- `lat`·`lng`는 휴게소 건물(지도 중심). `places`는 적재 때 카카오에서 받은 실제 위치의 장소(`rest_area_place`, erd.md). `kind`: `FOOD` `CAFE` `STORE` `GAS` `EV` `TOILET` `ATM`
- 장소 이름 앞 낱말이 0501 브랜드와 맞으면 `open`·`close`·`openNow`, 판매순위 매장과 맞으면 `topItems`가 붙습니다. 안 맞으면 null·빈 목록
- `building`: 지도에 따로 위치가 없는 것. `foodCourt.menus`는 0502 메뉴(베스트·추천·프리미엄 먼저). `store`는 메뉴 이름 앞 괄호의 가게(`(유가네)곰탕` → 유가네, 이름은 "곰탕"), 없으면 null(식당가 공용). `foodCourt.topItems`는 판매순위 중 식당가 코너(`한식`, `푸드(…)` 등). `placeId`는 그 가게가 `places`에 있으면 그 ID
- `otherStores`: 위치를 못 찾은 0501 매장과 판매순위 매장
- 없는 코드면 `404 PLACE_NOT_FOUND`

---

## 4. WebSocket 공통

### 4-1. 접속과 인증

1. `wss://{서버}/ws`에 접속합니다.
2. **5초 안에** 첫 메시지로 토큰을 보냅니다. 안 보내면 서버가 `4401`로 닫습니다.
```json
{"type": "auth", "data": {"token": "c_8Jx…"}}
```
3. 성공하면 서버가 `auth.ok`를 보냅니다.
```json
{"type": "auth.ok", "data": {
  "role": "CAR",
  "trip": {"tripId": "a1b2…", "startedAt": "…"},
  "openCall": null}}
```
- `role`: `CAR` | `PASSENGER`. 동승자에게는 `"me": {"passengerId": 12, "nickname": "민지"}`가 더 붙습니다.
- `trip`: 그 차량의 ACTIVE 세션, 없으면 `null`.
- `openCall`: 열려 있는 출발 알림이 있으면 `{"callId", "calledAt", "etaS", "destName", "people": [departure.status의 사람 …]}`, 없으면 `null` (폰을 늦게 열거나 차 앱을 다시 켜도 대화 화면으로 바로 가게).
- 동승자는 `auth.ok` 직후 서버가 `route.update`, `trip.state`를 한 번씩 보내 줍니다 (지도 그리기용).

### 4-2. 메시지 모양

```json
{"type": "waypoint.propose", "reqId": "f3a9", "data": { … }}
```
- `type`: 메시지 이름 (5~7장).
- `reqId`: 선택. 보낸 쪽이 붙이면 서버가 결과를 `ack` 또는 `error`로 같은 `reqId`를 붙여 돌려줍니다.
```json
{"type": "ack", "reqId": "f3a9", "data": {"requestId": 88}}
{"type": "error", "reqId": "f3a9", "data": {"code": "REQUEST_PENDING", "message": "운전자가 이전 요청을 확인하고 있어요"}}
```

### 4-3. 살아 있음 확인, 끊김

| 항목 | 규칙 |
|---|---|
| ping | 클라이언트가 **20초마다** `{"type": "ping"}`, 서버는 `{"type": "pong"}` |
| 세션 만료 | **차량 앱**의 메시지(ping 포함)가 5분 동안 없으면 세션 `EXPIRED`. 폰의 메시지는 세션 수명에 영향 없음 |
| 다시 붙기 | 끊기면 1초, 2초, 4초 … 최대 30초 간격으로 다시 접속하고 `auth`부터 다시 합니다 |
| 같은 차량 앱이 두 번 접속 | 나중 접속을 남기고 이전 것은 `4409`로 닫음. 동승자도 같음 (한 사람당 연결 1개) |
| 닫는 코드 | `4401` 인증 실패·시간 초과, `4403` 해제된 동승자, `4409` 다른 곳에서 접속 (`4410`은 쓰지 않음, 아래 구현 참고) |

> **구현 (2026-11-09)**: `4410`은 쓰지 않습니다. 세션이 끝나도(`ENDED`·`EXPIRED`) 동승자 연결은 그대로 두고 `trip.ended`만 보냅니다. 같은 연결로 다음 `trip.started`를 받습니다.

---

## 5. WebSocket: 차량 앱 → 서버

### 5-1. `trip.start` — 세션 시작

차량 쪽 위치 공유 동의 후. 이미 ACTIVE 세션이 있으면 그것을 돌려줍니다 (앱 재시작).
```json
{"type": "trip.start", "reqId": "1", "data": {"locationConsent": true}}
```
응답: `ack {"tripId": "a1b2…"}` + 폰들에게 `trip.started`

### 5-2. `trip.end` — 세션 끝

```json
{"type": "trip.end", "data": {}}
```
위치·목적지·에너지 값을 지우고 `ENDED`. 폰들에게 `trip.ended {"reason": "ENDED"}`.

### 5-3. `car.location` — 차 위치 (5초마다)

NaviHelper `getCurrentLocationInfo()` 값.
```json
{"type": "car.location", "data": {"lat": 36.62, "lng": 127.43, "heading": 182, "at": "…"}}
```
- 서버는 `trip.last_*`만 갱신합니다 (이력 없음). 경로선에서 **500m 이상, 1분 넘게** 벗어나면 길찾기를 다시 부릅니다 (api.md 11).
- 폰들에게 `trip.state`로 전달합니다.

### 5-4. `car.route` — 목적지·경유지 (바뀔 때만)

NaviHelper `onDrivingInfo`와 차량 앱이 기억하는 경유지 좌표를 합칩니다. **`DrivingInfo`에는 좌표가 없어서** (`DrivingState`는 이름·남은 거리·남은 시간만 있음), 경유지 좌표는 차량 앱이 `addWaypoint`할 때 쓴 값을 기억해 둡니다.
```json
{"type": "car.route", "data": {
  "destination": {"name": "부산역", "lat": 35.115, "lng": 129.041, "remainDistM": 312000, "remainTimeS": 12600},
  "waypoints": [
    {"index": "FIRST", "name": "경산(서울)휴게소", "lat": 35.86, "lng": 128.80,
     "remainDistM": 210000, "remainTimeS": 8100, "requestId": 88}
  ]}}
```
- 목적지가 없으면 `"destination": null`, `"waypoints": []`.
- 경유지 좌표를 모르면(운전자가 내비에서 직접 넣은 경유지) `lat`·`lng`를 `null`로. 서버는 그 경유지를 빼고 경로를 계산합니다.
- `requestId`: 우리 요청으로 넣은 경유지면 그 `waypoint_request.id`. A1 도착 안내에서 어떤 휴게소인지 찾는 데 씁니다.
- **남은 거리·시간만 바뀔 때는 30초에 1번만** 보냅니다 (목적지·경유지 구성이 바뀌면 바로).
- 구성이 바뀌면 서버가 길찾기를 다시 부르고 폰들에게 `route.update`.

### 5-5. `car.energy` — 연료·배터리 (바뀔 때만, 최대 1분에 1번)

```json
{"type": "car.energy", "data": {"energyType": "FUEL", "energyPct": 18, "rangeM": 61000}}
```
- Vehicle SDK 값 (way-on.md 8.3 표). `energyType`은 연료 %·배터리 % 중 값이 나오는 쪽.
- 값이 안 나오면 그 필드를 `null`로.

### 5-6. `car.gear` — 기어 (바뀔 때만)

```json
{"type": "car.gear", "data": {"gear": "PARK"}}
```
- `PARK` | `DRIVE` | `REVERSE` | `NEUTRAL` | `UNKNOWN`. 서버는 저장하지 않고 폰에 `trip.state`로 전달합니다 (폰이 "정차 중"을 표시하는 데만 씀).

### 5-7. `energy.low` — 에너지 제안 조건 충족 (F6)

**판단은 차량 앱이 합니다**: `rangeM < destination.remainDistM + 20000`. 목적지 1개당 1번, 목적지가 바뀌거나 `rangeM`이 다시 늘면(주유 후) 초기화.
```json
{"type": "energy.low", "data": {"energyType": "FUEL", "rangeM": 60000, "remainDistM": 50000}}
```
서버 동작: 경로 위에서 `rangeM` 안에 있는 주유소(연료) 또는 충전소(전기)를 찾아 `energy.suggest`를 보냅니다. 동승자가 있으면 폰들에게, 없으면 차량 앱에게 (정차 중 화면용). `trip.low_energy_alerted_at`을 채웁니다.

### 5-8. `parking.near-dest` — 목적지 근처 (N5)

**판단은 차량 앱**: 목적지 남은 거리 10km 이하 또는 남은 시간 5분 이하, 목적지 1개당 1번.
```json
{"type": "parking.near-dest", "data": {"remainDistM": 9800, "remainTimeS": 840}}
```
서버 동작: 목적지 반경 1km 공영주차장을 `parking_lot`에서 찾아 `parking.suggest`를 보냅니다 (동승자 있으면 폰, 없으면 차).

### 5-9. `restarea.approach`, `restarea.arrived`, `restarea.left` — 휴게소 도착 안내 (A1)

**경유지로 넣은 휴게소만**. 판단은 차량 앱.
| 메시지 | 조건 |
|---|---|
| `restarea.approach` | 그 경유지 남은 거리 2km 이하 |
| `restarea.arrived` | 경유지 도착 콜백(`onWayPointArrived`), 또는 `restarea.approach`를 보낸 뒤 기어 P (way-on.md 3.8) |
| `restarea.left` | 도착 후 기어 D |
```json
{"type": "restarea.approach", "data": {"requestId": 88}}
```
서버는 `requestId`로 `waypoint_request.place_ext_id`(`ra:000048`)를 찾아 폰들에게 `restarea.info`를 보냅니다.

### 5-10. `waypoint.accept`, `waypoint.reject` — 운전자 확인 (F3)

```json
{"type": "waypoint.accept", "reqId": "7", "data": {"requestId": 88}}
{"type": "waypoint.reject", "data": {"requestId": 88}}
```
- **처리 순서 (D7): 서버 선점 → 내비 호출 → 결과 기록.** 차량 앱은 `waypoint.accept`에 대한 `ack`를 받은 뒤에만 `addWaypoint`·`requestRoute`를 부릅니다. `error {"code": "REQUEST_EXPIRED"}`가 오면 부르지 않습니다.
- 10초 안에 아무것도 안 보내면 서버가 `EXPIRED`로 바꾸고 차량 앱에 `waypoint.expired`를 보냅니다.

### 5-11. `waypoint.result` — 내비 반영 결과

```json
{"type": "waypoint.result", "data": {"requestId": 88, "ok": true}}
{"type": "waypoint.result", "data": {"requestId": 88, "ok": false, "error": "ROUTE_FAILED", "message": "경로를 찾지 못했어요"}}
```
경유지는 `onWaypointChanged(ADD)`, 목적지는 `onRouteStarted` → `ok: true`(`APPLIED`). `onError` 또는 60초 동안 응답 없음 → `ok: false`(`FAILED`, `fail_reason`에 저장). `onRoutingCompleted`는 에뮬레이터에서 오지 않아 쓰지 않습니다 (2026-11-05 확인, car-app-design.md 10장). 폰에 보여 주고, 실패는 차에도 3초 보여 줍니다.

### 5-12. 출발해요 (A2) — 차량 앱이 보내는 것

출발 알림은 **차량 토큰만** 열 수 있습니다. 상세 규칙은 출발해요 기획 7장.
| 메시지 | data | 비고 |
|---|---|---|
| `departure.call` | `{"destName": "부산역", "etaS": 5400}` (둘 다 없어도 됨) | 정차 중에만 (차량 앱이 판단). 목적지 이름·남은 시간은 차량 앱이 내비 값으로 채워 알림 문구에 씀 (2026-11-09 추가). 응답 `ack {"callId", "calledAt", "etaS", "destName", "nextRestAreaM"}`를 먼저 보내고 그 뒤 `departure.status`. 이미 `OPEN`이 있으면 `error CALL_ALREADY_OPEN` |
| `departure.remind` | `{"callId": 40}` | 1분에 1번. 응답 없는 사람에게만 다시 푸시 |
| `departure.close` | `{"callId": 40, "reason": "DEPARTED"}` | `DEPARTED` 출발 / `CANCELLED` 취소 |
| `departure.message` | `{"callId": 40, "kind": "QUICK", "quickCode": "HURRY"}` 또는 `{"callId": 40, "kind": "TEXT", "body": "B동 앞이에요"}` | 운전자 빠른 답장은 `TAKE_TIME`, `HURRY`만 |

- 기어가 D가 되면 차량 앱이 `departure.close {reason: DEPARTED}`를 보냅니다.
- **받는 사람** (출발해요 기획 8장, way-on.md 3.9): 지금 세션 동승자 + **이 차량의 직전 세션(끝난 지 2시간 이내)에 있던 동승자**. 휴게소에서 시동을 꺼서 세션이 만료돼도 알림이 가게 하려는 것입니다. 해제된 동승자는 뺍니다. WebSocket이 붙어 있으면 `departure.called`, 아니면 Web Push (구독이 있을 때).

---

## 6. WebSocket: 폰 → 서버

### 6-1. `waypoint.propose` — 경유지 요청 (F3)

```json
{"type": "waypoint.propose", "reqId": "f3a9", "data": {
  "place": {"type": "RESTAURANT", "extId": "kakao:1234567", "name": "할매국밥",
            "address": "충북 청주시 …", "lat": 36.62, "lng": 127.43}}}
```
- `type`에 `PARKING`도 됩니다 (`extId` `pl:{parking_lot.id}`). 주차장은 경유지가 아니라 **목적지로** 바꿉니다 (way-on.md 3.6).
- `action`은 **서버가 정합니다**: 목적지 없음 → `SET_DEST`, 주차장 → `SET_DEST`, 그 밖 → `ADD`. **경유지 2개가 이미 찼으면 받지 않고** `error WAYPOINT_FULL`을 돌려줍니다 (차로 보내지 않음, 2026-11-05 결정).
- 응답 `ack {"requestId": 88}`. 이전 `PENDING` 요청이 있으면 그것을 `EXPIRED`로 바꾸고 새 요청을 받습니다 (마지막 요청 우선, way-on.md 5장 6번).
- 장소 정보는 폰이 보낸 값을 그대로 저장하지 않고, **서버가 `extId`로 다시 찾은 값**을 저장합니다 (폰이 좌표를 바꿔 보내는 것 방지). 다시 못 찾으면 `error PLACE_NOT_FOUND`.

### 6-2. `departure.message` — 출발해요 답장

```json
{"type": "departure.message", "data": {"callId": 40, "kind": "QUICK", "quickCode": "IN_LINE"}}
{"type": "departure.message", "data": {"callId": 40, "kind": "TEXT", "body": "5분만요!"}}
```
- 동승자 빠른 답장은 `TOILET`, `IN_LINE`, `COMING`만. `body`는 1~40자.
- 닫힌 알림이면 `error CALL_CLOSED`.

---

## 7. WebSocket: 서버 → 차량 앱·폰

| 메시지 | 받는 쪽 | data |
|---|---|---|
| `auth.ok` | 둘 다 | 4-1 |
| `trip.started` | 폰 | `{"tripId": "…", "startedAt": "…"}` |
| `trip.ended` | 폰 | `{"reason": "ENDED"}` 또는 `"EXPIRED"` |
| `trip.state` | 폰 | `{"car": {"lat", "lng", "heading", "at"}, "gear": "DRIVE", "energy": {"energyType", "energyPct", "rangeM"}}` 위치가 올 때마다 |
| `route.update` | 폰 | 아래 7-1 |
| `passengers.update` | 차 | `{"passengers": [{"passengerId", "nickname", "online", "pushEnabled"}]}` 연결·끊김·해제 때 |
| `waypoint.request` | 차 | 아래 7-2 |
| `waypoint.expired` | 차 | `{"requestId": 88}` 10초 초과 또는 새 요청이 와서 밀림 → 수락 화면 닫기 |
| `waypoint.status` | 폰 | `{"requestId": 88, "status": "APPLIED", "failReason": null}` 상태가 바뀔 때마다. 모든 동승자에게 (누가 요청했는지 `requestedBy` 닉네임 포함) |
| `energy.suggest` | 폰 (없으면 차) | `{"energyType", "rangeM", "remainDistM", "places": [3-6의 place …]}` |
| `parking.suggest` | 폰 (없으면 차) | 가까운 순으로 보냄. 폰에서 **가까운 순 / 싼 순**(`fee1h`, NULL은 맨 뒤)을 바꿔 봄 (way-on.md 8.1). `{"destName", "places": [{"extId": "pl:5120", "name", "address", "lat", "lng", "distM", "fee1h", "feeType", "totalSpaces", "nowParked", "openNow"}]}` |
| `restarea.info` | 폰 | `{"phase": "APPROACH" \| "ARRIVED" \| "LEFT", "restArea": 3-7 응답과 같은 모양}` (`LEFT`는 `restArea` 없이 닫기만) |
| `departure.called` | 폰 (+ Web Push) | `{"callId": 40, "calledAt", "etaS", "destName", "nextRestAreaM"}` |
| `departure.message` | 둘 다 | 2-5의 메시지 1개 |
| `departure.status` | 둘 다 | `{"callId": 40, "people": [{"passengerId": 12, "nickname": "민지", "lastQuickCode": "TOILET", "pushEnabled": true}]}` 답장이 올 때마다 |
| `departure.closed` | 둘 다 | `{"callId": 40, "reason": "DEPARTED"}` (`CANCELLED`, `EXPIRED`) |
| `ack`, `error`, `pong` | 둘 다 | 4-2, 4-3 |

### 7-1. `route.update`

```json
{"type": "route.update", "data": {
  "routeVersion": 3,
  "source": "KAKAO",
  "path": [[127.0425, 37.5046], [127.0431, 37.5040], …],
  "destination": {"name": "부산역", "lat": 35.115, "lng": 129.041},
  "waypoints": [{"name": "경산(서울)휴게소", "lat": 35.86, "lng": 128.80}],
  "distanceM": 395000, "durationS": 15600}}
```
- `path`는 `[lng, lat]` 배열 (카카오모빌리티 `vertexes` 순서와 같음). 점이 많으면 서버가 약 20m 간격으로 줄여서 보냅니다.
- `source`: `KAKAO` 길찾기 성공 / `APPROX` 실패해서 직선 근사 (way-on.md 3.4 7번). `APPROX`면 폰은 경로선을 점선으로 그립니다.
- 목적지가 없으면 `{"routeVersion": 4, "path": [], "destination": null, …}`.

### 7-2. `waypoint.request`

```json
{"type": "waypoint.request", "data": {
  "requestId": 88, "requestedBy": "민지",
  "place": {"type": "REST_AREA", "name": "경산(서울)휴게소", "address": "…", "lat": 35.86, "lng": 128.80},
  "action": "ADD",
  "expiresAt": "2026-11-05T08:20:10.000Z"}}
```
- `action`은 `ADD` | `SET_DEST`. `SET_DEST`면 차 화면에 "목적지를 ○○로 바꿀까요?"
- 주행 중에도 보냅니다. 수락 화면은 DO 화면으로 짧게 (way-on.md 3.5).

### 7-3. Web Push 내용

Service Worker가 받는 JSON. 페이지가 열려 있어도 `departure.called`와 같이 보냅니다 (알림 중복은 Service Worker가 `tag`로 합침).
```json
{"kind": "DEPARTURE_CALLED", "callId": 40, "title": "곧 출발해요",
 "body": "부산역까지 약 3시간 30분 · 답장해 주세요", "url": "/?call=40", "tag": "departure-40"}
```
- `notificationclick`에서 `clients.openWindow(url)`. 열려 있는 창이 있으면 그 창을 앞으로.
- 푸시 서버가 `404`·`410`을 주면 구독을 지웁니다 (erd.md 출발 알림 규칙).
- `departure.remind`는 같은 `tag`로 다시 보내 알림을 새로 띄웁니다.

---

## 8. 오류 코드

| 코드 | HTTP | 뜻 | 폰·차 화면 문구 (예) |
|---|---|---|---|
| `TOKEN_INVALID` | 401 | 토큰 없음·틀림·만료·해제 | "다시 연결해 주세요" |
| `FORBIDDEN_ROLE` | 403 | 차량만 할 수 있는 것을 동승자가 (또는 반대) | — |
| `CODE_INVALID` | 404 | QR 코드 없음·폐기됨 | "QR을 다시 띄워 주세요" |
| `CODE_EXPIRED` | 410 | QR 3분 지남 | "QR이 만료됐어요" |
| `NICKNAME_INVALID` | 400 | 1~10자 아님 | "닉네임은 1~10자로 정해 주세요" |
| `NICKNAME_TAKEN` | 409 | 같은 차에 같은 닉네임 | "이미 쓰는 닉네임이에요" |
| `PASSENGER_LIMIT` | 409 | 동승자 4명 | "동승자는 4명까지 연결할 수 있어요" |
| `CONSENT_REQUIRED` | 400 | 위치 공유 동의 안 함 | "위치 공유에 동의해야 연결돼요" |
| `TRIP_NOT_ACTIVE` | 404 | ACTIVE 세션 없음 | "차량 앱이 켜지면 연결돼요" |
| `NO_ROUTE` | 409 | 목적지 없음 | "목적지를 정하면 경로 위에서 찾아 드려요" |
| `PLACE_NOT_FOUND` | 404 | `extId`로 장소를 다시 못 찾음 | "장소 정보를 찾지 못했어요" |
| `WAYPOINT_FULL` | 409 | 경유지 2개가 이미 참 | "경유지가 가득 찼어요" |
| `REQUEST_EXPIRED` | 409 | 10초 지났거나 밀린 요청을 수락 | (차) 수락 화면 닫기 |
| `CALL_ALREADY_OPEN` | 409 | 열린 출발 알림이 있음 | — (기존 것을 보여 줌) |
| `CALL_CLOSED` | 409 | 닫힌 출발 알림에 답장 | "출발 알림이 끝났어요" |
| `REMIND_TOO_SOON` | 429 | 다시 알리기 1분 안 됨 | "잠시 뒤에 다시 알릴 수 있어요" |
| `MESSAGE_TOO_FAST` | 429 | 동승자가 출발해요 메시지를 1초에 2건 이상 | "조금 천천히 보내 주세요" |
| `MESSAGE_INVALID` | 400 | 빠른 답장 코드가 그 사람 것이 아님, 텍스트 1~40자 아님 | — |
| `EXTERNAL_UNAVAILABLE` | 503 | 외부 API 실패·한도 초과 (오피넷 300건 등) | "잠시 뒤에 다시 시도해 주세요" |
| `BAD_REQUEST` | 400 | 형식 틀림 | — |

WebSocket에서는 HTTP 상태 없이 `error` 메시지의 `code`만 씁니다.

---

## 9. 기준값, 정하지 않은 것

**기준값** (way-on.md 5장 12번. 서버 설정값으로 둠)
| 항목 | 값 |
|---|---|
| QR 만료 | 3분 (만료 전까지 여러 명) |
| 동승자 토큰 | 마지막 연결 후 90일 |
| 동승자 최대 | 4명 |
| 세션 만료 | 차량 앱 무통신 5분 |
| 수락 대기 | 10초 |
| 에너지 여유 | 20km |
| 휴게소 접근 | 경유지 남은 거리 2km |
| 주차장 | 목적지 10km 또는 5분, 반경 1km |
| 경로 다시 계산 | 경로선에서 500m 이상, 1분 이상 벗어남 |
| 출발 알림 | 다시 알리기 1분, 자동 만료 30분, 메시지 40자, 30일 보관 |
| ping | 20초 |

**이 문서에 넣지 않은 것 (팀 합의 후 추가)**
- N1 정차 중 차량 화면 장소 목록: 넣으면 3-6을 차량 토큰으로도 부를 수 있게 하면 됩니다.
- N2 경유 시 도착 시간 변화: 3-6 응답에 `detourS`를 더하려면 장소마다 길찾기를 더 불러야 해서 한도 확인이 먼저입니다.
- N3 연속 주행 2시간, N4 명소 칩, N8 "언제쯤?" 필터, N9 여정 요약.

**정하지 않은 것**
- 4자리 PIN 연결: 주행 중 sign-in에 해당하는지 Pleos 답을 기다리는 중이라 넣지 않았습니다 (`connect-codes`의 `kind`만 열어 둠).
- 폰의 위치를 서버에 보낼지: 지금 기능 중에는 폰 위치를 쓰는 곳이 없어서 넣지 않았습니다. `trip_passenger.consented_at`은 동의 기록만 합니다.
- 카카오모빌리티 하루 한도: 경로 다시 계산 기준(500m·1분)은 한도를 확인한 뒤 조정합니다.
