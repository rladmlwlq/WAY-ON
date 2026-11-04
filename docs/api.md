# 외부 API 목록

> 서버가 호출하는 외부 데이터 API를 정리한 문서입니다. 차량 앱과 동승자 웹은 외부 API를 직접 부르지 않고, 모두 서버를 거칩니다.
> **인증키는 이 문서에도, 코드에도 적지 않습니다.** 서버는 환경변수로 읽습니다. 키 값은 팀 메신저로 받으세요.

| # | 데이터 | API | 제공처 | 환경변수 | 상태 |
|---|---|---|---|---|---|
| 1 | 휴게소 좌표 | 고속도로 휴게소 기준정보 현황 (`locationinfoRest`) | 한국도로공사 | `EX_API_KEY` | ✅ 키 발급, 호출 확인 (2026-11-04) |
| 2 | 휴게소 방향·주소·편의시설 | 노선별 휴게시설 현황 (`serviceAreaRoute`) | 한국도로공사 | `EX_API_KEY` (1과 같은 키) | ✅ 호출 확인 (2026-11-04) |
| 3 | 전기차 충전소 | 한국환경공단 전기자동차 충전소 정보 (`getChargerInfo`, `getChargerStatus`) | 공공데이터포털 | `DATA_GO_KR_KEY` | ✅ 승인, 호출 확인 (2026-11-04). 기능별 하루 1,000건 |
| 4 | 주유소·유가 | 반경 내 주유소 검색 (`aroundAll`) | 오피넷 | `OPINET_KEY` | ✅ 키 발급, 호출 확인 (2026-11-04). **하루 300건** |
| 5 | 맛집 | Kakao Local 카테고리 검색 | 카카오 | `KAKAO_REST_KEY` | ✅ 키 발급, 호출 확인 (2026-11-04) |
| 6 | 동승자 웹 지도 | Kakao Maps JavaScript SDK | 카카오 | `KAKAO_JS_KEY` | ✅ 키 발급 (지도 표시는 웹 만들 때 확인) |

---

## 1. 고속도로 휴게소 기준정보 현황

> 데이터 포털 문서에는 "명칭, 위치, 운영 방향, 관리 주체, 주소, 행정구역"을 준다고 되어 있지만, **실제 응답에는 이름·노선·좌표·코드만** 나옵니다. 방향·주소는 2번 API에서 가져옵니다.

| 항목 | 값 |
|---|---|
| URL | `https://data.ex.co.kr/openapi/locationinfo/locationinfoRest` |
| 방식 | HTTPS GET |
| 포맷 | XML, JSON (`type`으로 선택) |
| 용도 | `rest_area` seed의 **좌표**(`lat`, `lng`) |

### Request
| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `key` | string | 필수 | 인증키 |
| `type` | string | 필수 | `json` 또는 `xml` |
| `unitCode` | string | 선택 | 휴게소코드 |
| `routeNo` | string | 선택 | 노선코드 |
| `stdRestCd` | string | 선택 | 휴게소/주유소코드 |
| `serviceAreaCode` | string | 선택 | 영업부대시설코드 |
| `numOfRows` | string | 선택 | 한 페이지당 건수. **최대 99** |
| `pageNo` | string | 선택 | 페이지 번호 |

```
GET /openapi/locationinfo/locationinfoRest?key={EX_API_KEY}&type=json&numOfRows=99&pageNo=1
```

### Response
| 필드 | 타입 | 설명 |
|---|---|---|
| `unitName` | string | 휴게소명. 방향이 괄호에 들어감 (예: `안성(부산)휴게소`) |
| `routeName` | string | 노선명 |
| `routeNo` | string | 노선코드 |
| `xValue` | string | **경도** (WGS84) |
| `yValue` | string | **위도** (WGS84) |
| `unitCode` | string | 휴게소코드 |
| `stdRestCd` | string | 휴게소/주유소코드 |
| `serviceAreaCode` | string | 영업부대시설코드 (`A00001` 형식) |
| `count` | string | 전체 건수 |
| `pageSize` | string | 전체 페이지 수 |
| `code` / `message` | string | 결과 (`SUCCESS` / `인증키가 유효합니다.`) |

```json
{
  "count": 203,
  "list": [
    {
      "unitName": "서울만남(부산)휴게소",
      "unitCode": "001",
      "routeName": "경부선",
      "routeNo": "0010",
      "xValue": "127.042514",
      "yValue": "37.459939",
      "stdRestCd": "000001",
      "serviceAreaCode": "A00001"
    }
  ],
  "pageNo": 1,
  "numOfRows": 5,
  "pageSize": 41,
  "message": "인증키가 유효합니다.",
  "code": "SUCCESS"
}
```

### 확인한 사실 (2026-11-04)
- 전체 **203건**, 22개 노선. 좌표가 빠진 행은 없음
- 상행·하행 휴게소가 **각각 다른 행, 다른 좌표**로 나옴
- 쉼터 4곳이 섞여 있음 (서울하이패스센터쉼터, 부안상쉼터, 부안하쉼터, 서하남행복드림쉼터) → seed에서 제외
- 이름에 방향 괄호가 없는 휴게소가 있음 (옥천만남, 기흥복합, 행담도, 하남드림, 마장, 덕평 등. 양방향 공용으로 추정)
- `curl` 기본 User-Agent로 부르면 `400 Request Blocked`. 서버에서 호출할 때 일반 User-Agent 헤더를 넣을 것

---

## 2. 노선별 휴게시설 현황

| 항목 | 값 |
|---|---|
| URL | `https://data.ex.co.kr/openapi/business/serviceAreaRoute` |
| 방식 | HTTPS GET |
| 포맷 | XML, JSON |
| 용도 | `rest_area`의 **방향**(`direction_label`), 주소, 편의시설 |

### Request
| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `key` | string | 필수 | 인증키 |
| `type` | string | 필수 | `json` 또는 `xml` |
| `routeName` | string | 선택 | 노선명 |
| `direction` | string | 선택 | 방향 |
| `serviceAreaName` | string | 선택 | 휴게소/주유소명 |
| `routeCode` | string | 선택 | 노선코드 |
| `serviceAreaCode` | string | 선택 | 영업부대시설코드 |
| `serviceAreaCode2` | string | 선택 | 휴게소/주유소코드 |
| `numOfRows` | string | 선택 | 한 페이지당 건수. 최대 99 |
| `pageNo` | string | 선택 | 페이지 번호 |

```
GET /openapi/business/serviceAreaRoute?key={EX_API_KEY}&type=json&numOfRows=99&pageNo=1
```

### Response
| 필드 | 타입 | 설명 |
|---|---|---|
| `serviceAreaName` | string | 휴게소/주유소명 |
| `routeName` / `routeCode` | string | 노선명 / 노선코드 |
| `direction` | string | **방향 이름** (예: `부산`, `서울`) |
| `serviceAreaCode` | string | 영업부대시설코드 (`A00001`) |
| `serviceAreaCode2` | string | 휴게소/주유소코드 (`000001`) |
| `svarAddr` | string | 주소 |
| `telNo` | string | 전화번호 |
| `convenience` | string | 편의시설. `|`로 구분 (예: `수유실|샤워실`) |
| `brand` | string | 입점 브랜드 (예: `탐엔탐스 외 3`) |
| `maintenanceYn` | string | 경정비 유무 (`O`/`X`) |
| `truckSaYn` | string | 화물휴게소 유무 (`O`/`X`) |
| `count` / `pageSize` / `code` / `message` | string | 1번과 같음 |

```json
{
  "count": 221,
  "list": [
    {
      "serviceAreaName": "서울만남(부산)휴게소",
      "routeName": "경부선",
      "routeCode": "0010",
      "direction": "부산",
      "serviceAreaCode": "A00001",
      "serviceAreaCode2": "000001",
      "svarAddr": "서울 서초구 원지동10-16",
      "telNo": "02-578-3372",
      "convenience": "수유실",
      "brand": "탐엔탐스 외 3",
      "maintenanceYn": "X",
      "truckSaYn": "X"
    }
  ]
}
```

### 확인한 사실 (2026-11-04)
- 전체 **221건**. 일부 행은 `routeName`, `serviceAreaName`이 `null` → 건너뜀
- 1번과 **`serviceAreaCode`로 연결**: 203건 중 172건 연결. 연결된 행은 `stdRestCd` = `serviceAreaCode2`로도 전부 일치
- 연결 안 된 31건(쉼터 4곳 포함)은 이름 괄호에서 방향을 꺼냄

---

## 1 + 2 → `rest_area` seed 매핑

| `rest_area` 컬럼 | 출처 |
|---|---|
| `name` | 1 `unitName` |
| `route_name` | 1 `routeName` |
| `direction_label` | 2 `direction`. 연결 안 되면 1 `unitName`의 괄호 안 |
| `lat` | 1 `yValue` |
| `lng` | 1 `xValue` |
| `travel_bearing` | **API에 없음.** 상·하행 좌표와 같은 노선의 앞뒤 휴게소 위치로 계산하거나, 시연 구간은 수동 입력 (미정) |
| (추가 제안) `ex_code` | 1 `serviceAreaCode`. 다른 도로공사 API와 연결할 키 |

휴게소 목록은 자주 바뀌지 않으므로 **seed 생성 시 한 번 호출**해서 SQL로 만듭니다 (D9). 앱 실행 중에는 호출하지 않습니다.

---

## 3. 한국환경공단 전기자동차 충전소 정보

| 항목 | 값 |
|---|---|
| End Point | `https://apis.data.go.kr/B552584/EvCharger` |
| 방식 | HTTPS GET (REST) |
| 포맷 | XML 기본, `dataType=JSON`으로 JSON |
| 신청 | data.go.kr 로그인 → [활용신청](https://www.data.go.kr/data/15076352/openapi.do) (자동승인). 개발계정 활용기간 2026-11-04 ~ 2028-11-04 |
| 인증키 | 공공데이터포털 계정의 **일반 인증키** (`DATA_GO_KR_KEY`, 계정당 1개) |
| **호출 한도** | 기능별 **하루 1,000건** |
| 규격 문서 | 포털의 `한국환경공단_전기자동차 충전소 정보_OpenAPI활용가이드_v1.25.docx` |

### 3-1. 충전소 정보 `/getChargerInfo`

**Request**
| 파라미터 | 설명 |
|---|---|
| `serviceKey` | 필수. 인증키 |
| `pageNo`, `numOfRows` | 페이지. **numOfRows 9999까지 됨** (확인) |
| `dataType` | `JSON` |
| `zcode` | 시도 코드 (예: `11` 서울) |
| `zscode` | 시군구 코드 (예: `11650` 서초구) |
| `kind`, `kindDetail` | 충전소 구분 |

```
GET /getChargerInfo?serviceKey={DATA_GO_KR_KEY}&pageNo=1&numOfRows=9999&zcode=11&dataType=JSON
```

**Response** (`items.item[]`, **충전기 1대당 1행**)
| 필드 | 설명 |
|---|---|
| `statId` / `statNm` | 충전소 ID / 이름 (같은 충전소에 여러 행) |
| `chgerId` | 충전기 ID (충전소 안 번호) |
| `chgerType` | 충전기 타입 코드 (DC콤보, AC완속 등. 코드표는 가이드 docx) |
| `lat`, `lng` | **위경도 (WGS84)** |
| `addr`, `addrDetail`, `location` | 주소, 상세 위치 |
| `useTime` | 운영 시간 |
| `busiNm`, `busiCall` | 운영기관, 전화 |
| `stat` | 상태 코드 (아래) |
| `statUpdDt` | 상태 갱신 시각 (`yyyyMMddHHmmss`) |
| `output` | 충전 용량 (kW) |
| `parkingFree` | 주차 무료 `Y`/`N` |
| `limitYn`, `limitDetail` | 이용 제한 여부 (외부인 불가 등) |
| `delYn` | 삭제 여부 |
| `zcode`, `zscode` | 시도 / 시군구 코드 |
| `kind`, `kindDetail` | 충전소 구분 |
| `totalCount` | 전체 건수 |

**`stat` 상태 코드** (가이드 docx로 재확인): `1` 통신이상, `2` 충전대기(**사용 가능**), `3` 충전중, `4` 운영중지, `5` 점검중, `9` 상태미확인

### 3-2. 충전기 상태 `/getChargerStatus`

**Request**: `serviceKey`, `pageNo`, `numOfRows`, `dataType`, `period` (최근 N분 안에 **상태가 바뀐 충전기만**), `zcode` 등

```
GET /getChargerStatus?serviceKey={DATA_GO_KR_KEY}&pageNo=1&numOfRows=9999&period=5&dataType=JSON
```

**Response**: `busiId`, `statId`, `chgerId`, `stat`, `statUpdDt`, `lastTsdt`(마지막 충전 시작), `lastTedt`(마지막 충전 종료), `nowTsdt`(현재 충전 시작). **좌표 없음** → 3-1과 `statId`+`chgerId`로 연결

### 확인한 사실 (2026-11-04)
- 키 동작 확인 (기상청 신청 때 받은 계정 키 그대로)
- **좌표·반경 검색 파라미터가 없음.** 지역 코드로만 거를 수 있음
- 전국 **534,495행**(충전기 수), 서울만 77,042행. 서울 9,999행 = 충전소 약 3,000곳
- 최근 5분 상태 변경: 전국 약 9,100건 → `numOfRows=9999`면 한 번에 받음

### ⚠️ 설계 영향: D9("충전소는 실시간 호출, DB 저장 안 함")은 그대로는 안 됨
요청마다 "현재 위치 근처 충전소"를 물어볼 방법이 없습니다. 대신:
1. **충전소 정보는 서버 DB에 주기적으로 저장** (하루 1번, 전국 약 54번 호출. 급속만 거르면 더 작음)
2. **상태는 `getChargerStatus?period=5`로 몇 분마다 바뀐 것만 갱신** (호출 1번에 전국분)
3. 경로 주변 검색은 우리 DB에서 좌표로 계산 (휴게소와 같은 방식)

하루 1,000건 안에 충분히 들어감 (정보 54 + 상태 5분 간격 288 ≈ 340건)

## 4. 오피넷 반경 내 주유소 검색

| 항목 | 값 |
|---|---|
| URL | `https://www.opinet.co.kr/api/aroundAll.do` |
| 방식 | HTTPS GET |
| 포맷 | XML, JSON (`out`으로 선택) |
| 용도 | 연료 부족 시 차량 주변 주유소·가격 제안 (F6) |
| 신청 | opinet.co.kr 로그인 → 무료 API 이용 신청. 규격은 같은 페이지의 "무료 API 이용 가이드" PDF 16번 |
| **호출 한도** | **하루 300건** (일반 Key, 개인) |

### Request
| 파라미터 | 설명 |
|---|---|
| `code` | 필수. 인증키 (`OPINET_KEY`) |
| `x`, `y` | 필수. 기준 좌표. **KATEC 좌표계** (위경도 아님) |
| `radius` | 필수. 반경(m). **최대 5000** |
| `prodcd` | 필수. `B027` 보통휘발유, `B034` 고급휘발유, `D047` 자동차용경유, `K015` 자동차용부탄(LPG), `C004` 실내등유. **한 번에 한 제품 가격만** 나옴 |
| `sort` | 필수. `1` 가격순, `2` 거리순 |
| `out` | 필수. `json` 또는 `xml` |

```
GET /api/aroundAll.do?code={OPINET_KEY}&x=314681.8&y=544837&radius=3000&prodcd=B027&sort=1&out=json
```

### Response
| 필드 | 설명 |
|---|---|
| `RESULT.OIL[]` | 주유소 목록 |
| `UNI_ID` | 주유소 고유 ID |
| `POLL_DIV_CD` | 상표. `SKE` SK에너지, `GSC` GS칼텍스, `HDO` HD현대오일뱅크, `SOL` S-OIL, `RTO` 알뜰, `RTX` 알뜰(ex), `NHO` NH-OIL, `SKG` SK가스, `E1G` E1, `ETC` 자가상표 |
| `OS_NM` | 주유소 이름 |
| `PRICE` | 가격 (원/L) |
| `DISTANCE` | 기준 좌표에서의 거리 (m) |
| `GIS_X_COOR`, `GIS_Y_COOR` | 주유소 좌표 (**KATEC**) |

```json
{"RESULT": {"OIL": [
  {"UNI_ID": "A0000866", "POLL_DIV_CD": "GSC", "OS_NM": "반포그린주유소",
   "PRICE": 1865, "DISTANCE": 1949.8,
   "GIS_X_COOR": 312740.19440, "GIS_Y_COOR": 545022.51520}
]}}
```

### 확인한 사실 (2026-11-04)
- 키 동작 확인 (서울 서초 부근 KATEC 좌표, 반경 3km로 호출)
- **좌표가 KATEC**이라 서버에서 변환 필요: 차량 위치(WGS84) → KATEC으로 요청, 결과(KATEC) → WGS84로 지도 표시
- **하루 300건** (가이드 기준. 2026-09-01에 1,500건에서 300건으로 줄었음) → 같은 위치 근처 요청은 서버에서 몇 분간 캐시. 시연·전시 기간에는 한도 증설 문의 필요
- 응답 JSON에 공백·줄바꿈이 많이 섞여 있지만 파싱에는 문제 없음

## 5. Kakao Local 카테고리 검색 (맛집)

| 항목 | 값 |
|---|---|
| URL | `https://dapi.kakao.com/v2/local/search/category.json` (키워드 검색은 `/v2/local/search/keyword.json`) |
| 방식 | HTTPS GET, 응답 JSON |
| 인증 | 헤더 `Authorization: KakaoAK {KAKAO_REST_KEY}` |
| 신청 | developers.kakao.com → 내 애플리케이션 `WAY:ON` → 플랫폼 키 |
| 용도 | 경로 주변 맛집 (F2). 2026-11-04 회의에서 카카오로 결정 |

### Request
| 파라미터 | 설명 |
|---|---|
| `category_group_code` | 필수. `FD6` 음식점, `CE7` 카페 (그 밖에 `OL7` 주유소·충전소, `PK6` 주차장 등) |
| `x`, `y` | 중심 좌표. **경도, 위도 (WGS84)** |
| `radius` | 반경(m). 최대 20000 |
| `sort` | `distance` 거리순, `accuracy` 정확도순 |
| `page`, `size` | size 최대 15, 한 검색당 최대 45건(3페이지) |

```
GET /v2/local/search/category.json?category_group_code=FD6&x=127.131800&y=37.076443&radius=2000&sort=distance&size=15
Authorization: KakaoAK {KAKAO_REST_KEY}
```

### Response
| 필드 | 설명 |
|---|---|
| `documents[]` | 장소 목록 |
| `id` | 장소 ID |
| `place_name` | 이름 |
| `category_name` | 분류 (예: `음식점 > 한식 > 국밥`) |
| `phone` | 전화 |
| `address_name`, `road_address_name` | 지번 / 도로명 주소 |
| `x`, `y` | 경도, 위도 (WGS84) |
| `distance` | 중심에서의 거리(m). `x`,`y`를 줬을 때만 |
| `place_url` | 카카오맵 장소 페이지 |
| `meta.total_count`, `meta.pageable_count`, `meta.is_end` | 전체 건수, 받을 수 있는 건수, 마지막 페이지 여부 |

```json
{"documents": [
  {"id": "2084343848", "place_name": "곰작골나주곰탕 안성휴게소서울방향점",
   "category_name": "음식점 > 한식 > 국밥 > 곰작골나주곰탕",
   "phone": "031-611-5793", "road_address_name": "경기 안성시 원곡면 경부고속도로 372",
   "x": "127.13249796334", "y": "37.076772904267", "distance": "71",
   "place_url": "http://place.map.kakao.com/2084343848"}
 ],
 "meta": {"total_count": 13, "pageable_count": 13, "is_end": false}}
```

### 확인한 사실 (2026-11-04)
- REST 키 동작 확인 (안성(서울)휴게소 좌표, 반경 2km 음식점)
- **좌표·반경 검색이 됨** → 실시간 호출 가능 (D9 유지)
- 고속도로 위에서 검색하면 **휴게소 안 식당가**가 먼저 나옴. 고속도로 밖 맛집을 원하면 IC 근처 좌표로 검색하거나 결과를 걸러야 함 (기획 결정 필요)
- 한 번에 최대 45건이라, 경로 주변 넓은 범위는 여러 좌표로 나눠 호출

## 6. Kakao Maps JavaScript SDK (동승자 웹 지도)

- 키: `KAKAO_JS_KEY` (웹페이지에 노출되는 키. 등록 도메인에서만 동작)
- 등록 도메인: `http://localhost:8080`, `http://localhost:5173`. 배포 주소와 폰 테스트용 PC IP는 필요할 때 추가
- 카카오맵 사용 설정 ON 완료 (2026-11-04)
- 로드: `<script src="//dapi.kakao.com/v2/maps/sdk.js?appkey={KAKAO_JS_KEY}"></script>`
