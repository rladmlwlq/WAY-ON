-- Co-Pilot Connect 스키마 (design.md 1.4, 3.5 기준)
-- 시간은 모두 UTC, 좌표는 WGS84 위경도

CREATE TABLE rest_area (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  name              VARCHAR(50)  NOT NULL,
  route_name        VARCHAR(50)  NOT NULL,
  direction_label   VARCHAR(20)  NOT NULL,            -- 예: 서울방향
  lat               DECIMAL(9,6) NOT NULL,
  lng               DECIMAL(9,6) NOT NULL,
  travel_bearing    SMALLINT     NOT NULL,            -- 진입 차량 진행 방향 0~359°. 상·하행 구분용
  entry_radius_m    SMALLINT     NOT NULL DEFAULT 300, -- GPS 오차가 휴게소마다 달라서 개별 조정
  layout_image_path VARCHAR(200),
  layout_w          INT,
  layout_h          INT,
  updated_at        DATETIME(3)  NOT NULL
);

CREATE TABLE facility (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  rest_area_id BIGINT      NOT NULL,
  type         VARCHAR(20) NOT NULL,                  -- TOILET / FOOD_COURT / STORE / FUEL / EV_CHARGER / NURSING_ROOM
  name         VARCHAR(50) NOT NULL,
  layout_x     INT         NOT NULL,                  -- 배치도 이미지 기준 픽셀
  layout_y     INT         NOT NULL,
  open_hours   VARCHAR(50),
  note         VARCHAR(200),
  CONSTRAINT fk_facility_ra FOREIGN KEY (rest_area_id) REFERENCES rest_area(id),
  INDEX idx_facility_ra (rest_area_id)
);

CREATE TABLE trip (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  public_id        CHAR(32)     NOT NULL UNIQUE,      -- URL용. 순번 노출을 막습니다
  car_token_hash   CHAR(64)     NOT NULL,
  phone_token_hash CHAR(64)     NOT NULL,
  status           VARCHAR(10)  NOT NULL,             -- ACTIVE / ENDED
  dest_name        VARCHAR(100),
  dest_lat DECIMAL(9,6), dest_lng DECIMAL(9,6),
  last_lat DECIMAL(9,6), last_lng DECIMAL(9,6), last_heading SMALLINT,
  last_reported_at DATETIME(3),
  created_at       DATETIME(3)  NOT NULL,
  expires_at       DATETIME(3)  NOT NULL,
  INDEX idx_trip_expires (status, expires_at)
);

CREATE TABLE waypoint_request (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  trip_id       BIGINT      NOT NULL,
  rest_area_id  BIGINT      NOT NULL,
  status        VARCHAR(10) NOT NULL,                 -- PENDING / ACCEPTED / APPLIED / REJECTED / FAILED / EXPIRED
  requested_at  DATETIME(3) NOT NULL,
  decided_at    DATETIME(3),
  fail_reason   VARCHAR(200),
  CONSTRAINT fk_wr_trip FOREIGN KEY (trip_id) REFERENCES trip(id),
  CONSTRAINT fk_wr_ra   FOREIGN KEY (rest_area_id) REFERENCES rest_area(id),
  INDEX idx_wr_trip_status (trip_id, status)
);

-- 여행당 마지막 주차 위치 1건만 보관 (위치정보 최소 보관)
CREATE TABLE parking_record (
  trip_id      BIGINT       PRIMARY KEY,
  rest_area_id BIGINT,                                -- 휴게소 밖 주차면 NULL (Q6)
  lat          DECIMAL(9,6) NOT NULL,
  lng          DECIMAL(9,6) NOT NULL,
  parked_at    DATETIME(3)  NOT NULL,
  CONSTRAINT fk_pr_trip FOREIGN KEY (trip_id) REFERENCES trip(id),
  CONSTRAINT fk_pr_ra   FOREIGN KEY (rest_area_id) REFERENCES rest_area(id)
);
