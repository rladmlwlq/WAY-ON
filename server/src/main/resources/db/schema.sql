-- Co-Pilot Connect 스키마 (docs/erd.md 기준)
-- 시간은 모두 UTC, 좌표는 WGS84 위경도

CREATE TABLE vehicle (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '차량 ID',
  public_id       CHAR(32)    NOT NULL UNIQUE COMMENT '공개 ID',
  car_token_hash  CHAR(64)    NOT NULL UNIQUE COMMENT '차량 토큰 해시(SHA-256)',
  created_at      DATETIME(3) NOT NULL COMMENT '등록 시각',
  last_seen_at    DATETIME(3) COMMENT '마지막 접속 시각'
) COMMENT '차량 (차량 앱 설치 1개)';

CREATE TABLE passenger (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '동승자 ID',
  vehicle_id            BIGINT      NOT NULL COMMENT '차량 ID',
  reconnect_token_hash  CHAR(64)    NOT NULL UNIQUE COMMENT '재연결 토큰 해시(SHA-256)',
  label                 VARCHAR(30) COMMENT '표시 이름',
  created_at            DATETIME(3) NOT NULL COMMENT '등록 시각',
  last_connected_at     DATETIME(3) COMMENT '마지막 연결 시각',
  revoked_at            DATETIME(3) COMMENT '해제 시각',              -- NULL이면 유효
  CONSTRAINT fk_passenger_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle(id),
  INDEX idx_passenger_vehicle (vehicle_id, revoked_at)
) COMMENT '등록 동승자 (자동 재연결)';

CREATE TABLE connect_code (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '연결 코드 ID',
  vehicle_id  BIGINT      NOT NULL COMMENT '차량 ID',
  kind        VARCHAR(5)  NOT NULL COMMENT '종류',                     -- QR / PIN
  code_hash   CHAR(64)    NOT NULL COMMENT '코드 해시(SHA-256)',
  expires_at  DATETIME(3) NOT NULL COMMENT '만료 시각',
  used_at     DATETIME(3) COMMENT '사용 시각',                        -- 한 번 쓰면 폐기
  fail_count  SMALLINT    NOT NULL DEFAULT 0 COMMENT '실패 횟수',      -- 4자리 PIN 대입 공격 제한
  CONSTRAINT fk_cc_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle(id),
  INDEX idx_cc_lookup (kind, code_hash, expires_at)
) COMMENT '일회용 연결 코드 (QR 토큰, 4자리 코드)';

CREATE TABLE trip (
  id                     BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '세션 ID',
  public_id              CHAR(32)     NOT NULL UNIQUE COMMENT '공개 ID',   -- URL용. 순번 노출을 막습니다
  vehicle_id             BIGINT       NOT NULL COMMENT '차량 ID',
  status                 VARCHAR(10)  NOT NULL COMMENT '상태',             -- ACTIVE / ENDED / EXPIRED
  car_consented_at       DATETIME(3)  COMMENT '차량 위치 공유 동의 시각',
  dest_name              VARCHAR(100) COMMENT '목적지 이름',
  dest_lat               DECIMAL(9,6) COMMENT '목적지 위도',
  dest_lng               DECIMAL(9,6) COMMENT '목적지 경도',
  last_lat               DECIMAL(9,6) COMMENT '마지막 위도',               -- 이력 없이 마지막 위치만 (위치정보 최소 보관)
  last_lng               DECIMAL(9,6) COMMENT '마지막 경도',
  last_heading           SMALLINT     COMMENT '진행 방향(0~359)',
  last_reported_at       DATETIME(3)  COMMENT '마지막 위치 보고 시각',
  energy_type            VARCHAR(5)   COMMENT '에너지 종류',               -- EV / FUEL
  energy_pct             TINYINT      COMMENT '배터리·연료 잔량(%)',
  low_energy_alerted_at  DATETIME(3)  COMMENT '에너지 부족 제안 시각',     -- 같은 세션에 반복 제안하지 않게
  created_at             DATETIME(3)  NOT NULL COMMENT '시작 시각',
  last_activity_at       DATETIME(3)  NOT NULL COMMENT '마지막 통신 시각',  -- 5분 무통신이면 만료
  ended_at               DATETIME(3)  COMMENT '종료 시각',
  CONSTRAINT fk_trip_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle(id),
  INDEX idx_trip_vehicle_status (vehicle_id, status),
  INDEX idx_trip_activity (status, last_activity_at)
) COMMENT '주행 세션';

CREATE TABLE trip_passenger (
  trip_id       BIGINT      NOT NULL COMMENT '세션 ID',
  passenger_id  BIGINT      NOT NULL COMMENT '동승자 ID',
  joined_at     DATETIME(3) NOT NULL COMMENT '연결 시각',
  consented_at  DATETIME(3) NOT NULL COMMENT '폰 위치 공유 동의 시각',
  PRIMARY KEY (trip_id, passenger_id),
  CONSTRAINT fk_tp_trip      FOREIGN KEY (trip_id) REFERENCES trip(id),
  CONSTRAINT fk_tp_passenger FOREIGN KEY (passenger_id) REFERENCES passenger(id)
) COMMENT '세션 연결 동승자';

CREATE TABLE waypoint_request (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '요청 ID',
  trip_id       BIGINT       NOT NULL COMMENT '세션 ID',
  passenger_id  BIGINT       NOT NULL COMMENT '요청한 동승자 ID',
  place_type    VARCHAR(15)  NOT NULL COMMENT '장소 종류',     -- REST_AREA / EV_CHARGER / GAS_STATION / RESTAURANT
  place_ext_id  VARCHAR(50)  COMMENT '외부 데이터 ID',
  poi_name      VARCHAR(100) NOT NULL COMMENT '장소 이름',     -- 외부 장소는 DB에 없어서 요청 당시 값을 그대로 저장
  address       VARCHAR(200) COMMENT '주소',
  lat           DECIMAL(9,6) NOT NULL COMMENT '위도',
  lng           DECIMAL(9,6) NOT NULL COMMENT '경도',
  action        VARCHAR(15)  NOT NULL COMMENT '동작',          -- ADD / REPLACE_FIRST / SET_DEST
  status        VARCHAR(10)  NOT NULL COMMENT '상태',          -- PENDING / ACCEPTED / APPLIED / REJECTED / FAILED / EXPIRED
  requested_at  DATETIME(3)  NOT NULL COMMENT '요청 시각',
  decided_at    DATETIME(3)  COMMENT '결정 시각',
  fail_reason   VARCHAR(200) COMMENT '실패 이유',
  CONSTRAINT fk_wr_trip      FOREIGN KEY (trip_id) REFERENCES trip(id),
  CONSTRAINT fk_wr_passenger FOREIGN KEY (passenger_id) REFERENCES passenger(id),
  INDEX idx_wr_trip_status (trip_id, status)
) COMMENT '경유지 요청';

CREATE TABLE rest_area (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '휴게소 ID',
  name             VARCHAR(50)  NOT NULL COMMENT '휴게소 이름',
  route_name       VARCHAR(50)  NOT NULL COMMENT '노선명',
  direction_label  VARCHAR(20)  NOT NULL COMMENT '방향',                -- 예: 서울방향
  lat              DECIMAL(9,6) NOT NULL COMMENT '위도',
  lng              DECIMAL(9,6) NOT NULL COMMENT '경도',
  travel_bearing   SMALLINT     NOT NULL COMMENT '진입 진행 방향(0~359)', -- 반대 차로 휴게소 제외용
  updated_at       DATETIME(3)  NOT NULL COMMENT '갱신 시각'
) COMMENT '휴게소 (상·하행 각각 1행)';

CREATE TABLE facility (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '시설 ID',
  rest_area_id  BIGINT      NOT NULL COMMENT '휴게소 ID',
  type          VARCHAR(20) NOT NULL COMMENT '시설 종류',   -- TOILET / FOOD_COURT / STORE / FUEL / EV_CHARGER / NURSING_ROOM
  name          VARCHAR(50) NOT NULL COMMENT '시설 이름',
  open_hours    VARCHAR(50) COMMENT '운영 시간',
  CONSTRAINT fk_facility_ra FOREIGN KEY (rest_area_id) REFERENCES rest_area(id),
  INDEX idx_facility_ra (rest_area_id)
) COMMENT '휴게소 시설';
