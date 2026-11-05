-- WAY:ON 스키마 (docs/erd.md 기준)
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
  nickname              VARCHAR(10) NOT NULL COMMENT '닉네임 (연결 때 필수 입력, 같은 차 안에서 겹치지 않음)',
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

CREATE TABLE ev_station (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '충전소 ID',
  stat_id          VARCHAR(20)  NOT NULL UNIQUE COMMENT '환경부 충전소 ID(statId)',
  name             VARCHAR(100) NOT NULL COMMENT '충전소 이름',
  addr             VARCHAR(200) NOT NULL COMMENT '주소',
  location_detail  VARCHAR(200) COMMENT '상세 위치',
  lat              DECIMAL(9,6) NOT NULL COMMENT '위도',
  lng              DECIMAL(9,6) NOT NULL COMMENT '경도',
  use_time         VARCHAR(100) COMMENT '운영 시간',
  operator_name    VARCHAR(50)  NOT NULL COMMENT '운영기관',
  operator_phone   VARCHAR(30)  COMMENT '운영기관 전화',
  parking_free     BOOLEAN      COMMENT '주차 무료 여부',
  access_limited   BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '외부인 이용 제한 여부',
  limit_detail     VARCHAR(200) COMMENT '이용 제한 내용',
  zcode            CHAR(2)      NOT NULL COMMENT '시도 코드',
  synced_at        DATETIME(3)  NOT NULL COMMENT '마지막 동기화 시각',
  INDEX idx_evs_lat_lng (lat, lng)
) COMMENT '전기차 충전소 (환경부, 하루 1번 동기화)';

CREATE TABLE ev_charger (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '충전기 ID',
  station_id       BIGINT      NOT NULL COMMENT '충전소 ID',
  chger_id         VARCHAR(10) NOT NULL COMMENT '환경부 충전기 ID(충전소 안 번호)',
  chger_type       CHAR(2)     NOT NULL COMMENT '충전기 타입 코드',
  output_kw        SMALLINT    COMMENT '충전 용량(kW)',
  stat             TINYINT     NOT NULL COMMENT '상태',                  -- 1 통신이상 / 2 사용가능 / 3 충전중 / 4 운영중지 / 5 점검중 / 9 미확인
  stat_updated_at  DATETIME(3) COMMENT '상태 갱신 시각(환경부 statUpdDt)',
  synced_at        DATETIME(3) NOT NULL COMMENT '마지막 동기화 시각',
  CONSTRAINT fk_evc_station FOREIGN KEY (station_id) REFERENCES ev_station(id) ON DELETE CASCADE,
  UNIQUE KEY uk_evc_station_chger (station_id, chger_id),
  INDEX idx_evc_station_stat (station_id, stat)
) COMMENT '충전기 (정보 하루 1번, 상태 5분마다)';

CREATE TABLE parking_lot (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '주차장 ID',
  source          VARCHAR(10)  NOT NULL COMMENT '출처',                  -- NATIONAL(전국 표준데이터) / SEOUL(서울시)
  source_key      VARCHAR(40)  NOT NULL COMMENT '출처별 ID',             -- NATIONAL: insttCode:prkplceNo / SEOUL: PKLT_CD
  name            VARCHAR(100) NOT NULL COMMENT '주차장 이름',
  kind            VARCHAR(10)  NOT NULL COMMENT '유형',                  -- OFF_STREET 노외 / ON_STREET 노상 / ATTACHED 부설
  addr            VARCHAR(200) NOT NULL COMMENT '주소',
  lat             DECIMAL(9,6) NOT NULL COMMENT '위도',
  lng             DECIMAL(9,6) NOT NULL COMMENT '경도',
  coord_source    VARCHAR(10)  NOT NULL COMMENT '좌표 출처',              -- API / GEOCODE(카카오 주소 검색)
  total_spaces    INT          COMMENT '총 주차면',
  fee_type        VARCHAR(5)   NOT NULL COMMENT '요금 구분',              -- FREE / PAID / MIXED
  basic_min       SMALLINT     COMMENT '기본 시간(분)',
  basic_fee       INT          COMMENT '기본 요금(원)',
  add_min         SMALLINT     COMMENT '추가 단위 시간(분)',
  add_fee         INT          COMMENT '추가 단위 요금(원)',
  day_max_fee     INT          COMMENT '일 최대 요금(원)',
  fee_1h          INT          COMMENT '1시간 요금(원, 정렬용)',          -- 계산 불가면 NULL → 싼 순에서 맨 뒤
  weekday_open    CHAR(4)      COMMENT '평일 시작(HHMM)',
  weekday_close   CHAR(4)      COMMENT '평일 종료(HHMM, 2400까지)',
  sat_open        CHAR(4)      COMMENT '토요일(서울은 주말) 시작',
  sat_close       CHAR(4)      COMMENT '토요일(서울은 주말) 종료',
  holiday_open    CHAR(4)      COMMENT '공휴일 시작',
  holiday_close   CHAR(4)      COMMENT '공휴일 종료',
  phone           VARCHAR(30)  COMMENT '전화',
  now_parked      INT          COMMENT '현재 주차 대수(서울 실시간, 일부만)',
  now_updated_at  DATETIME(3)  COMMENT '현재 주차 대수 갱신 시각',
  ref_date        DATE         COMMENT '출처 데이터 기준일',
  synced_at       DATETIME(3)  NOT NULL COMMENT '마지막 동기화 시각',
  UNIQUE KEY uk_pl_source (source, source_key),
  INDEX idx_pl_lat_lng (lat, lng)
) COMMENT '주차장 (전국 + 서울, 한 달 1번 동기화)';
