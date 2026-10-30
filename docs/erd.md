# Co-Pilot Connect ERD

> 기준: `server/src/main/resources/db/schema.sql` (design.md 1.4, 3.5)
> DB: MySQL 8.4, utf8mb4, 시간은 모두 UTC

```mermaid
erDiagram
    REST_AREA ||--o{ FACILITY : "시설을 가짐"
    REST_AREA ||--o{ WAYPOINT_REQUEST : "요청 대상"
    REST_AREA |o--o{ PARKING_RECORD : "주차 위치(휴게소 밖이면 없음)"
    TRIP ||--o{ WAYPOINT_REQUEST : "요청을 받음"
    TRIP ||--o| PARKING_RECORD : "마지막 주차 1건"

    REST_AREA {
        BIGINT id PK
        VARCHAR(50) name "휴게소 이름"
        VARCHAR(50) route_name "노선명"
        VARCHAR(20) direction_label "예: 서울방향"
        DECIMAL(9_6) lat
        DECIMAL(9_6) lng
        SMALLINT travel_bearing "진입 차량 진행 방향 0~359"
        SMALLINT entry_radius_m "진입 판단 반경, 기본 300"
        VARCHAR(200) layout_image_path "배치도 이미지 (NULL 가능)"
        INT layout_w "NULL 가능"
        INT layout_h "NULL 가능"
        DATETIME(3) updated_at
    }

    FACILITY {
        BIGINT id PK
        BIGINT rest_area_id FK
        VARCHAR(20) type "TOILET, FOOD_COURT, STORE, FUEL, EV_CHARGER, NURSING_ROOM"
        VARCHAR(50) name
        INT layout_x "배치도 픽셀 좌표"
        INT layout_y "배치도 픽셀 좌표"
        VARCHAR(50) open_hours "NULL 가능"
        VARCHAR(200) note "NULL 가능"
    }

    TRIP {
        BIGINT id PK
        CHAR(32) public_id UK "URL용 공개 ID"
        CHAR(64) car_token_hash "차 토큰 SHA-256"
        CHAR(64) phone_token_hash "폰 토큰 SHA-256"
        VARCHAR(10) status "ACTIVE, ENDED"
        VARCHAR(100) dest_name "NULL 가능"
        DECIMAL(9_6) dest_lat "NULL 가능"
        DECIMAL(9_6) dest_lng "NULL 가능"
        DECIMAL(9_6) last_lat "마지막 위치만, 종료 시 NULL"
        DECIMAL(9_6) last_lng "마지막 위치만, 종료 시 NULL"
        SMALLINT last_heading "NULL 가능"
        DATETIME(3) last_reported_at "NULL 가능"
        DATETIME(3) created_at
        DATETIME(3) expires_at "최대 24시간"
    }

    WAYPOINT_REQUEST {
        BIGINT id PK
        BIGINT trip_id FK
        BIGINT rest_area_id FK
        VARCHAR(10) status "PENDING, ACCEPTED, APPLIED, REJECTED, FAILED, EXPIRED"
        DATETIME(3) requested_at
        DATETIME(3) decided_at "NULL 가능"
        VARCHAR(200) fail_reason "NULL 가능"
    }

    PARKING_RECORD {
        BIGINT trip_id PK, FK "여행당 1건"
        BIGINT rest_area_id FK "휴게소 밖 주차면 NULL"
        DECIMAL(9_6) lat
        DECIMAL(9_6) lng
        DATETIME(3) parked_at
    }
```

> Mermaid 문법상 타입 안의 쉼표를 쓸 수 없어서 `DECIMAL(9,6)`을 `DECIMAL(9_6)`으로 표기했습니다.

## 테이블 요약

| 테이블 | 한 행의 의미 | 성격 |
|---|---|---|
| `rest_area` | 휴게소 1곳의 한 방향 (상·하행은 각각 1행) | 기준 데이터. seed.sql로 입력, 서버 시작 때 메모리에 올림 |
| `facility` | 휴게소 안 시설 1개 | 기준 데이터 |
| `trip` | 여행 1건 (차 1대 ↔ 동승자 폰들) | 운행 데이터. 종료 시 위치 삭제 |
| `waypoint_request` | 동승자의 경유지 요청 1건과 운전자 결정 | 운행 데이터 |
| `parking_record` | 여행의 마지막 주차 위치 1건 | 운행 데이터. 종료 시 삭제 |

## 관계

| 관계 | 카디널리티 | 설명 |
|---|---|---|
| rest_area → facility | 1 : N | 휴게소 하나에 시설 여러 개 |
| trip → waypoint_request | 1 : N | 여행 중 요청은 여러 번. 대기(PENDING)는 동시에 1건만 (새 요청이 오면 이전 것은 EXPIRED) |
| rest_area → waypoint_request | 1 : N | 요청 대상 휴게소 |
| trip → parking_record | 1 : 0..1 | 여행당 마지막 주차 위치만 보관 (UPSERT) |
| rest_area → parking_record | 0..1 : N | 휴게소 밖에 주차하면 rest_area_id가 NULL (Q6) |

## 상태 값

**trip.status**: `ACTIVE` → `ENDED`

**waypoint_request.status** (허용 전이, design.md 3.4)
```
PENDING  → ACCEPTED | REJECTED | EXPIRED
ACCEPTED → APPLIED  | FAILED
```

## 인덱스

| 테이블 | 인덱스 | 용도 |
|---|---|---|
| trip | `public_id` UNIQUE | URL의 tripId로 조회 |
| trip | `idx_trip_expires (status, expires_at)` | 만료된 여행 정리 배치 |
| waypoint_request | `idx_wr_trip_status (trip_id, status)` | 여행의 대기 요청 조회 |
| facility | `idx_facility_ra (rest_area_id)` | 휴게소별 시설 조회 |
