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
| 7 | 목적지 근처 주차장 | 전국주차장정보표준데이터 (`tn_pubr_prkplce_info_api`) | 공공데이터포털 | `DATA_GO_KR_KEY` (3과 같은 키) | ✅ 자동 승인, 호출 확인 (2026-11-05). 하루 10,000건 |
| 8 | 서울 주차장 보강 + 실시간 대수 | 서울시 공영주차장 (`GetParkInfo`, `GetParkingInfo`) | 서울 열린데이터광장 | `SEOUL_DATA_KEY` | ✅ 키 발급, 호출 확인 (2026-11-05) |
| 9 | 주소 → 좌표 변환 (주차장 적재용) | Kakao Local 주소 검색 | 카카오 | `KAKAO_REST_KEY` (5와 같은 키) | ✅ 호출 확인 (2026-11-05) |
| 10 | 휴게소 음식 Top5 | 휴게소별 상품 월별 판매 상위 5 (**파일, 매월 수동 다운로드**) | 한국도로공사 | — | ✅ 2026-10월분 받아서 확인 (2026-11-05) |
| 11 | 경로선 (경로 주변 계산) | 카카오모빌리티 길찾기 (`/v1/directions`) | 카카오모빌리티 | `KAKAO_REST_KEY` (5와 같은 키) | ✅ 호출 확인 (2026-11-05). 한도 미확인 |

### 언제 부르나

| # | 호출 시점 | 저장 |
|---|---|---|
| 1, 2, 0501~0504 | seed 만들 때 (가끔, 수동) | DB (`rest_area`, `facility` 등) |
| 10 | 매월 파일 받아서 적재 (수동) | DB |
| 3 | 충전소 정보 하루 1번, 상태 5분마다 (배치) | DB (`ev_station`, `ev_charger`) |
| 7, 8-1, 9 | 한 달 1번 (배치) | DB (`parking_lot`) |
| 8-2 | 5분마다 (배치) | DB (`parking_lot.now_parked`) |
| 4, 5 | **사용자 요청 때 실시간** (4는 서버 캐시) | 저장 안 함 |
| 11 | 세션 시작·목적지/경유지 변경·경로 이탈 때 | 서버 메모리 (세션별 경로선) |
| 6 | 동승자 브라우저가 직접 로드 | — |

---

## 0. 공통 규칙 (모든 외부 호출)

- **User-Agent 헤더를 꼭 넣기** (예: `WayOn/0.1`). 도로공사는 curl 기본 UA면 `400 Request Blocked`
- **타임아웃** 연결 5초, 읽기 30초 (충전소 9,999행·주차장 1,000행 응답이 1MB 안팎). 배치는 실패하면 다음 주기에 다시 하고, 사용자 요청(4, 5)은 실패하면 빈 목록 + 폰에 "잠시 후 다시" 안내
- **숫자도 문자열로 오는 API가 많음** (도로공사, 환경공단, 주차장 7번). DTO는 `String`으로 받고 변환할 때 빈 문자열(`""`)·`null`을 같이 처리
- 공공데이터포털 키(`DATA_GO_KR_KEY`)는 지금 영문·숫자 64자라 인코딩 문제가 없음. 키에 `+`, `/`, `=`가 있는 형식이면 **Decoding 키**를 `.env`에 두고 HTTP 클라이언트가 인코딩하게 함 (Encoding 키를 넣으면 두 번 인코딩돼서 `SERVICE_KEY_IS_NOT_REGISTERED_ERROR`)
- 한글 쿼리(카카오 주소 등)는 UTF-8 URL 인코딩. Windows Git Bash `curl`은 한글이 깨지므로 테스트는 Postman·Node·서버 코드로

### 실패하면 어떻게 오나 (2026-11-05 잘못된 키·빈 페이지로 직접 확인)

| API | 인증 실패 | 데이터 없음 (페이지 넘침 등) | 성공 판단 |
|---|---|---|---|
| 도로공사 (1, 2, 0501~0504) | **HTTP 200** + `{"code":"ERROR","message":"인증키가 유효하지 않습니다.","list":null,"count":0}` | HTTP 200 + `"code":"SUCCESS"`, `"list":[]` | `code == "SUCCESS"` |
| 환경공단 (3) | **HTTP 403** + `{"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR","returnReasonCode":"30",...}}}` | HTTP 200 + `resultCode "00"`, `items`가 비거나 없음 | `resultCode == "00"` |
| 주차장 표준데이터 (7) | 3과 같음 (HTTP 403, `OpenAPI_ServiceResponse`) | HTTP 200 + `{"header":{"resultCode":"03","resultMsg":"NODATA_ERROR"},"body":null}` | `header.resultCode == "00"` |
| 서울시 (8) | HTTP 200 + **JSON을 요청해도 XML**로 `<RESULT><CODE>INFO-100</CODE>...` | HTTP 200 + `{"RESULT":{"CODE":"INFO-200","MESSAGE":"해당하는 데이터가 없습니다."}}` (서비스 이름 감싸는 객체 없음) | `{서비스명}.RESULT.CODE == "INFO-000"` |
| 오피넷 (4) | ⚠️ **오류 없이** HTTP 200 + `{"RESULT":{"OIL":[]}}` → 빈 결과와 구분 안 됨. 서버 시작 때 알려진 좌표로 한 번 불러 키를 확인 | 위와 같음 | `OIL` 배열 |
| 카카오 (5, 9) | HTTP 401 + `{"errorType":"AccessDeniedError","message":"wrong appKey(...) format"}` | HTTP 200 + `"documents":[]` | HTTP 200 |
| 카카오모빌리티 (11) | HTTP 401 + `{"code":-401,"msg":"wrong appKey(...) format"}` | HTTP 200 + `routes[0].result_code != 0` (예: `104`) | `routes[0].result_code == 0` |

공공데이터포털 `returnReasonCode` 주요 값: `30` 등록되지 않은 키(활용신청 전이거나 키 인코딩 문제), `22` 하루 호출 한도 초과, `12` 없는 서비스, `99` 기타.
서울시 `CODE`: `INFO-000` 정상, `INFO-100` 키 오류, `INFO-200` 데이터 없음, `ERROR-336` 한 번에 1,000건 초과, `ERROR-500`·`600`·`601` 서버 오류.

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
| `std_rest_cd` | 1 `stdRestCd` (`000001`). 0501~0503과 연결하는 코드. 0504(주유소)와 판매순위 파일(10)은 코드가 안 맞아 **이름으로** 연결 |

휴게소 목록은 자주 바뀌지 않으므로 **seed 생성 시 한 번 호출**해서 SQL로 만듭니다 (D9). 앱 실행 중에는 호출하지 않습니다.

---

## 1·2 외 도로공사 API (같은 `EX_API_KEY`, URL만 바꿔 호출)

포털 "고속도로 휴게소현황" 분류. 신청 없이 같은 키로 됨. 규격은 포털 상세 페이지(`https://data.ex.co.kr/openapi/basicinfo/openApiInfoM?apiId={apiId}`) 기준.

**공통 Request:** `key`*, `type`*(json), `numOfRows`, `pageNo`, `routeCd`/`routeNm`, `stdRestCd`/`stdRestNm` (`*` 필수)
**공통 Response:** `stdRestCd`, `stdRestNm`, `routeCd`, `routeNm`, `svarAddr`, `code`, `message`, `count`, `pageSize` + 아래 고유 필드
**연결:** 0501·0502·0503의 `stdRestCd`(`000001` 형식)는 1번 API의 `stdRestCd`와 같은 체계 → 코드로 연결. **0504는 주유소 코드**라서 휴게소와 이름으로 연결 (아래 0504 참고)
**페이지:** `numOfRows` 최대 99. `pageSize`(전체 페이지 수)까지 `pageNo`를 늘려 가며 부름. 페이지를 넘기면 `list: []`

### 쓰기로 한 4개 (2026-11-04)

| apiId | API | URL (`https://data.ex.co.kr/openapi/...`) | 고유 Request | 고유 Response | 쓸 곳 |
|---|---|---|---|---|---|
| 0502 | 휴게소 푸드메뉴현황 | `restinfo/restBestfoodList` | `recommendyn`, `bestfoodyn`, `premiumyn` (구분 필터) | `seq`, `foodNm`(메뉴), `foodCost`(가격), `etc`(설명), `recommendyn`(추천), `bestfoodyn`(베스트), `premiumyn`(프리미엄), `seasonMenu`(`4` 사계절/`S` 여름/`W` 겨울), `foodMaterial`(재료), `app`(하이쉼마루 앱 표출 여부) | 휴게소 도착 모드, 미리 주문 |
| 0501 | 휴게소 브랜드 매장현황 | `restinfo/restBrandList` | — | `brdCode`, `brdName`(매장), `brdDesc`(비고), `stime`~`etime`(영업시간 `HH:mm`) | 도착 모드 (지금 영업 중인 매장) |
| 0503 | 휴게소 편의시설 현황 | `restinfo/restConvList` | — | `psCode`, `psName`(시설명), `psDesc`(상세), `stime`~`etime`(운영시간) | 휴게소 카드 아이콘(수유실 등), 필터 |
| 0504 | 휴게소 편의시설 현황(주유소) | `restinfo/restOilList` | (페이지 파라미터 없음. `routeCd`로 노선 단위 조회) | `psCode`, `psName`(세차장·쉼터·기타 등), `psDesc`(상세. 예: "기본세차 8,000원", "수소충전소 운영"), `stime`~`etime` | 휴게소 주유소의 **부대시설**(세차, 수소충전 등). 유종·가격은 없음 → 가격은 오피넷. 오피넷과 겹치지 않고 보완 관계라 **킵** |

호출 확인: 0501, 0502, 0503, 0504 (2026-11-04). 0503은 전체 1,288건, 시설명은 수유실·쉼터·ATM·편의점·수면실·샤워실·세탁실·약국·휴게소의원 등이고 **화장실 항목은 없음**. curl 기본 User-Agent로 부르면 `400 Request Blocked`가 나와서 User-Agent를 지정해야 함. 포털 규격과 대조 완료(4개 모두 일치).

- ~~주유소 코드는 휴게소 코드 + 1~~ → **틀림 (2026-11-05 전체 확인).** 이름으로 짝을 찾은 164곳 중 +1인 곳은 60곳뿐 (예: 양산(서울)주유소 `000061` ↔ 휴게소 `000062`, 진영(부산)주유소 `000065` ↔ 휴게소 `000063`). 0504를 휴게소에 붙일 때는 **이름으로** 연결 ("○○(방향)주유소" ↔ "○○(방향)휴게소")
- 포털 설명에 나오는 매장명·영양정보(0502), 유종·운영사(0504), 매장유형·위치·전화번호(0501)는 **실제 출력에 없음**

### 0502 휴게소 푸드메뉴현황 (상세)

| 항목 | 값 |
|---|---|
| URL | `https://data.ex.co.kr/openapi/restinfo/restBestfoodList` |
| 방식 | HTTPS GET |
| 포맷 | XML, JSON (`type`으로 선택) |
| 용도 | 휴게소 메뉴·가격. Top5(판매순위 파일)에 **가격을 붙이는 보완용** |

**Request**
| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `key` | string | 필수 | 인증키 (`EX_API_KEY`) |
| `type` | string | 필수 | `json` / `xml` |
| `numOfRows` | string | 선택 | 한 페이지 건수 (99까지 확인) |
| `pageNo` | string | 선택 | 페이지 번호 |
| `routeCd` / `routeNm` | string | 선택 | 노선 코드 / 노선명 |
| `stdRestCd` / `stdRestNm` | string | 선택 | 휴게소 코드 / 휴게소명 |
| `recommendyn` | string | 선택 | 추천 메뉴만 (`Y`) |
| `bestfoodyn` | string | 선택 | 베스트푸드만 (`Y`) |
| `premiumyn` | string | 선택 | 프리미엄 메뉴만 (`Y`) |

```
GET /restinfo/restBestfoodList?key={EX_API_KEY}&type=json&numOfRows=99&pageNo=1&stdRestCd=000048
```

**Response** (`list[]`, 메뉴 1개당 1행)
| 필드 | 타입 | 설명 |
|---|---|---|
| `stdRestCd` / `stdRestNm` | string | 휴게소 코드 / 이름 |
| `restCd` | string | 휴게소 코드 (`S` + `stdRestCd`, 7,279건 전부 같은 규칙) |
| `routeCd` / `routeNm` | string | 노선 코드 / 노선명 |
| `svarAddr` | string | 휴게소 주소 |
| `seq` | string | 메뉴 ID |
| `foodNm` | string | 메뉴 이름 |
| `foodCost` | string | 가격 (원, 문자열) |
| `etc` | string | 설명 (대부분 `null`) |
| `recommendyn` / `bestfoodyn` / `premiumyn` | string | 추천 / 베스트 / 프리미엄 `Y`·`N` |
| `seasonMenu` | string | `4` 사계절, `S` 여름, `W` 겨울 |
| `foodMaterial` | string | 재료 (대부분 `null`) |
| `app` | string | 하이쉼마루 앱 표출 여부 |
| `lastId`, `lastDtime`, `lsttmAltrUser`, `lsttmAltrDttm` | string | 등록자·등록일·수정자·수정일 (안 씀) |
| `count`, `pageNo`, `numOfRows`, `pageSize` | | 전체 건수, 페이지 정보 |
| `code`, `message` | string | `SUCCESS` / `인증키가 유효합니다.` |

```json
{"list": [
  {"stdRestCd": "000048", "stdRestNm": "경산(서울)휴게소", "restCd": "S000048",
   "routeCd": "0010", "routeNm": "경부선", "svarAddr": "경북 경산시 진량면신상리 995-3",
   "seq": "657", "foodNm": "돼지김치찌게", "foodCost": "9000", "etc": null,
   "recommendyn": "N", "seasonMenu": "4", "bestfoodyn": "Y", "premiumyn": "N", "app": "Y",
   "foodMaterial": null, "lastId": "...", "lastDtime": "2025-01-23",
   "lsttmAltrUser": "SYSTEM", "lsttmAltrDttm": "2026-11-05", "pageNo": null, "numOfRows": null}
 ],
 "count": 27, "pageNo": 1, "numOfRows": 2, "pageSize": 14,
 "code": "SUCCESS", "message": "인증키가 유효합니다."}
```

**확인한 사실 (2026-11-05, 전체 7,279건)**
- 응답에 **매장명이 없음** → 같은 휴게소 안에서 어느 매장 메뉴인지 모름
- **판매순위 파일과 휴게소 코드가 다름**: 0502 `restCd`는 경산(서울) `S000048`, 판매순위 파일은 같은 휴게소가 `S000418` → 두 데이터는 **휴게소 이름으로** 연결
- 판매순위 음식(편의점 제외 1,195행) 중 휴게소가 0502에 있는 1,062행, 그중 **메뉴 이름이 0502와 맞아 가격을 붙일 수 있는 것 406행 (약 38%)**. 이름은 괄호 접두어(`(할리스)` 등)와 공백을 지우고 비교. 나머지는 가격 없이 표시

### 0501 휴게소 브랜드 매장현황 (상세)

| 항목 | 값 |
|---|---|
| URL | `https://data.ex.co.kr/openapi/restinfo/restBrandList` |
| 방식 | HTTPS GET |
| 포맷 | XML, JSON (`type`으로 선택) |
| 용도 | 휴게소에 있는 **프랜차이즈 브랜드**와 영업시간 (지금 영업 중인지) |

**Request**
| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `key` | string | 필수 | 인증키 (`EX_API_KEY`) |
| `type` | string | 필수 | `json` / `xml` |
| `numOfRows` | string | 선택 | 한 페이지 건수 (99까지 확인) |
| `pageNo` | string | 선택 | 페이지 번호 |
| `routeCd` / `routeNm` | string | 선택 | 노선 코드 / 노선명 |
| `stdRestCd` / `stdRestNm` | string | 선택 | 휴게소 코드 / 휴게소명 |

```
GET /restinfo/restBrandList?key={EX_API_KEY}&type=json&numOfRows=99&pageNo=1&stdRestCd=000048
```

**Response** (`list[]`, 브랜드 매장 1개당 1행)
| 필드 | 타입 | 설명 |
|---|---|---|
| `stdRestCd` / `stdRestNm` | string | 휴게소 코드 / 이름 |
| `routeCd` / `routeNm` | string | 노선 코드 / 노선명 |
| `svarAddr` | string | 휴게소 주소 |
| `brdCode` | string | 브랜드 코드 (브랜드 종류 코드. 휴게소끼리 같은 브랜드면 같은 값) |
| `brdName` | string | 브랜드명 |
| `brdDesc` | string | 상세 (예: "06:00~21:00 유인운영, 21:00~06:00 무인운영"). `null` 가능 |
| `stime` ~ `etime` | string | 영업시간 `HH:mm` (`24:00`까지) |
| `redId`, `redDtime`, `lsttmAltrUser`, `lsttmAltrDttm` | string | 등록자·등록일(`yy/MM/dd`)·수정자·수정일 (안 씀) |
| `count`, `pageNo`, `numOfRows`, `pageSize` | | 전체 건수, 페이지 정보 |
| `code`, `message` | string | `SUCCESS` / `인증키가 유효합니다.` |

```json
{"list": [
  {"stdRestCd": "000048", "stdRestNm": "경산(서울)휴게소", "routeCd": "0010", "routeNm": "경부선",
   "svarAddr": "경북 경산시 진량면신상리 995-3",
   "brdCode": "084", "brdName": "이마트24", "brdDesc": "06:00~21:00 유인운영, 21:00~06:00 무인운영",
   "stime": "00:00", "etime": "24:00",
   "redId": "...", "redDtime": "26/06/15", "lsttmAltrUser": "SYSTEM", "lsttmAltrDttm": "2026-11-05",
   "pageNo": null, "numOfRows": null}
 ],
 "count": 5, "pageNo": 1, "numOfRows": 3, "pageSize": 2,
 "code": "SUCCESS", "message": "인증키가 유효합니다."}
```

**확인한 사실 (2026-11-05, 전체 699건)**
- 휴게소 239곳, 699개 매장. **프랜차이즈 브랜드만** 있음 (로띠번 77, 탐앤탐스 62, 할리스 56, CU 49, 롯데리아 12 …). "말죽거리한식당" 같은 휴게소 자체 식당은 없음 → 판매순위 파일의 매장명과는 대부분 안 맞음
- 포털 설명의 매장유형·위치·전화번호는 **출력에 없음** (설명만 있고 실제 필드 없음)
- 영업시간은 전부 채워져 있음. 상세(`brdDesc`)는 470건

### 0503 휴게소 편의시설 현황 (상세)

| 항목 | 값 |
|---|---|
| URL | `https://data.ex.co.kr/openapi/restinfo/restConvList` |
| 방식 | HTTPS GET |
| 포맷 | XML, JSON (`type`으로 선택) |
| 용도 | 휴게소 카드 아이콘(수유실·샤워실 등), "수유실 있는 휴게소" 같은 필터 → `facility` |

**Request**
| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `key` | string | 필수 | 인증키 (`EX_API_KEY`) |
| `type` | string | 필수 | `json` / `xml` |
| `numOfRows` | string | 선택 | 한 페이지 건수 (99까지 확인) |
| `pageNo` | string | 선택 | 페이지 번호 |
| `routeCd` / `routeNm` | string | 선택 | 노선 코드 / 노선명 |
| `stdRestCd` / `stdRestNm` | string | 선택 | 휴게소 코드 / 휴게소명 |

```
GET /restinfo/restConvList?key={EX_API_KEY}&type=json&numOfRows=99&pageNo=1&stdRestCd=000048
```

**Response** (`list[]`, 시설 1개당 1행)
| 필드 | 타입 | 설명 |
|---|---|---|
| `stdRestCd` / `stdRestNm` | string | 휴게소 코드 / 이름 |
| `routeCd` / `routeNm` | string | 노선 코드 / 노선명 |
| `svarAddr` | string | 휴게소 주소 |
| `psCode` | string | 편의시설 코드 (아래 표) |
| `psName` | string | 편의시설 이름 |
| `psDesc` | string | 상세. `null` 가능 |
| `stime` ~ `etime` | string | 운영시간 `HH:mm` (`24:00`까지) |
| `redId`, `redDtime`, `lsttmAltrUser`, `lsttmAltrDttm` | string | 수정자·수정일(`yy/MM/dd`) (안 씀) |
| `count`, `pageNo`, `numOfRows`, `pageSize` | | 전체 건수, 페이지 정보 |
| `code`, `message` | string | `SUCCESS` / `인증키가 유효합니다.` |

```json
{"list": [
  {"stdRestCd": "000048", "stdRestNm": "경산(서울)휴게소", "routeCd": "0010", "routeNm": "경부선",
   "svarAddr": "경북 경산시 진량면신상리 995-3",
   "psCode": "01", "psName": "수면실", "psDesc": null, "stime": "00:00", "etime": "24:00",
   "redId": "...", "redDtime": "21/11/16", "lsttmAltrUser": "SYSTEM", "lsttmAltrDttm": "2026-11-05",
   "pageNo": null, "numOfRows": null}
 ],
 "count": 9, "pageNo": 1, "numOfRows": 2, "pageSize": 5,
 "code": "SUCCESS", "message": "인증키가 유효합니다."}
```

**`psCode` 코드표** (전체 1,288건에서 확인, 괄호는 건수)

| 코드 | 이름 | 코드 | 이름 |
|---|---|---|---|
| `01` | 수면실 (48) | `09` | 경정비 (20) |
| `02` | 샤워실 (67) | `10` | 내고장특산물 (82) |
| `03` | 세탁실 (49) | `11` | 버스환승 (13) |
| `04` | 수유실 (236) | `12` | ATM (222) |
| `05` | 약국 (9) | `13` | 편의점 (179) |
| `06` | 세차장 (11) | `14` | 휴게소의원 (5) |
| `07` | 쉼터 (95) | `15` | 열린매장(간식) (78) |
| `08` | ex-화물차라운지 (62) | `99` | 기타 (112) |

**확인한 사실 (2026-11-05)**
- 휴게소 250곳, 1,288건. 운영시간은 전부 있음, 상세(`psDesc`)는 954건
- **화장실 코드가 없음** (모든 휴게소에 있어서 목록에 안 넣은 것으로 보임)
- 포털 설명의 "설치여부", "위치"는 **출력에 없음**. 목록에 있으면 설치된 것
- `facility.type`은 이 코드를 그대로 쓰는 게 단순함 (erd.md의 `TOILET` 등 임의 값 대신)

### 0504 휴게소 편의시설 현황(주유소) (상세)

| 항목 | 값 |
|---|---|
| URL | `https://data.ex.co.kr/openapi/restinfo/restOilList` |
| 방식 | HTTPS GET |
| 포맷 | XML, JSON (`type`으로 선택) |
| 용도 | 휴게소 주유소의 **부대시설** (세차, 수소충전, 쉼터 등). 유종·가격은 없음 → 가격은 오피넷 |

**Request** (페이지 파라미터 없음. 아무 필터 없이 부르면 전체 432건이 한 번에 옴)
| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `key` | string | 필수 | 인증키 (`EX_API_KEY`) |
| `type` | string | 필수 | `json` / `xml` |
| `routeCd` / `routeNm` | string | 선택 | 노선 코드 / 노선명 |
| `stdRestCd` / `stdRestNm` | string | 선택 | **주유소** 코드 / 주유소명 (휴게소 코드 아님) |

```
GET /restinfo/restOilList?key={EX_API_KEY}&type=json&stdRestCd=000049
```

**Response** (`list[]`, 부대시설 1개당 1행)
| 필드 | 타입 | 설명 |
|---|---|---|
| `stdRestCd` / `stdRestNm` | string | **주유소** 코드 / 이름 (예: `경산(서울)주유소`) |
| `routeCd` / `routeNm` | string | 노선 코드 / 노선명 |
| `svarAddr` | string | 주유소 주소 |
| `psCode` / `psName` | string | 시설 코드 / 이름 (0503과 같은 코드 체계, `13`만 "매점") |
| `psDesc` | string | 상세 (예: "기본세차 8,000원 …", "수소충전소 운영 …"). `null` 가능 |
| `stime` ~ `etime` | string | 운영시간 `HH:mm` |
| `redId`, `redDtime`, `lsttmAltrUser`, `lsttmAltrDttm` | string | 수정자·수정일 (안 씀) |
| `count`, `code`, `message` | | 전체 건수, 결과 (페이지 정보 필드 없음) |

```json
{"list": [
  {"stdRestCd": "000049", "stdRestNm": "경산(서울)주유소", "routeCd": "0010", "routeNm": "경부선",
   "svarAddr": "경북 경산시 진량면신상리 대학로 1089",
   "psCode": "07", "psName": "쉼터",
   "psDesc": "음용수, 커피, 사탕, PC(컴퓨터), FAX(팩스), Wi-Fi 무료제공 및 상비약 구비",
   "stime": "00:00", "etime": "24:00",
   "redId": "...", "redDtime": "16/03/10", "lsttmAltrUser": "SYSTEM", "lsttmAltrDttm": "2026-11-05"}
 ],
 "count": 2, "code": "SUCCESS", "message": "인증키가 유효합니다."}
```

**확인한 사실 (2026-11-05, 전체 432건)**
- 주유소 226곳. 시설: 쉼터 179, 기타 103, 샤워실 64, 세탁실 24, 수면실 15, 경정비 14, 세차장 13 …
- **수소충전**은 별도 코드가 없고 `99 기타`의 `psDesc`에 "수소충전소 운영"으로 들어 있음 → 글자로 찾아야 함
- 포털 설명의 주유소명·위치·유종·운영사·설치여부 중 **유종·운영사·설치여부는 출력에 없음**
- 휴게소와는 **이름으로** 연결 (코드 +1 규칙 안 맞음, 위 참고). 226곳 중 62곳은 같은 이름의 휴게소가 1번 API 목록에 없음

### 보류

| apiId | API | URL | 내용 |
|---|---|---|---|
| 0505 | 휴게소 이벤트 현황 | `restinfo/restEventList` | 이벤트 제목·내용·기간 |
| 0506 | 테마휴게소 현황 | `restinfo/restThemeList` | 테마명, 상세 |
| 0507 | 휴게소 청년창업매장 현황 | `restinfo/restVentureList` | 매장명, 영업시간 |
| 0508 | 휴게소별 날씨 정보 | `restinfo/restWeatherList` | 필수 `sdate`, `stdHour`. 기온·체감온도·강수·적설·풍속 등. **날씨 기능을 다시 넣으면 기상청 대신 이걸로 가능** |
| 0509 | 주유소 가격과 고속도로 통행량의 상관성 | `restinfo/restOilpriceList` | 분석용 |
| — | 고속도로 휴게소 기준정보(상하행·주차) | `restinfo/hiwaySvarInfoList` | 상/하행, 소형·대형·장애인 주차 대수. 주유소 포함 583건 (호출 확인) |
| — | 휴게소 편의시설 (영업부대시설 기준) | `business/conveniServiceArea` | 2번 API와 비슷한 형식 (호출 확인) |

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
| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `serviceKey` | string | 필수 | 인증키 (`DATA_GO_KR_KEY`) |
| `pageNo` | int | 필수 | 페이지 번호 |
| `numOfRows` | int | 필수 | 한 페이지 건수. **최소 10, 최대 9999** (1을 넣어도 10건 옴) |
| `dataType` | string | 필수 | `JSON` / `XML` (안 넣으면 XML) |
| `zcode` | string | 선택 | 시도 코드 (아래 코드표). 전국 동기화는 시도별로 나눠 부름 |
| `zscode` | string | 선택 | 시군구 코드 5자리 (예: `11650` 서초구) |
| `kind` / `kindDetail` | string | 선택 | 충전소 구분 (아래 코드표) |
| `statId` / `chgerId` | string | 선택 | 특정 충전소·충전기만 (가이드에는 필수로 적혀 있지만 빼고 불러도 됨) |

```
GET https://apis.data.go.kr/B552584/EvCharger/getChargerInfo?serviceKey={DATA_GO_KR_KEY}&pageNo=1&numOfRows=9999&zcode=11&dataType=JSON
```

**Response** (`items.item[]`, **충전기 1대당 1행**. 모든 값이 문자열)
| 필드 | 설명 |
|---|---|
| `resultCode` / `resultMsg` | `00` / `NORMAL SERVICE.` |
| `totalCount`, `pageNo`, `numOfRows` | 전체 충전기 수, 페이지 정보 (숫자). 마지막 페이지 = `ceil(totalCount / numOfRows)` |
| `statId` / `statNm` | 충전소 ID / 이름. **같은 충전소에 충전기 수만큼 행이 반복** |
| `chgerId` | 충전기 ID (충전소 안 번호, `01`) |
| `chgerType` | 충전기 타입 (아래 코드표) |
| `addr`, `addrDetail`, `location` | 주소, 주소 상세, 상세 위치 (빈 문자열 가능) |
| `lat`, `lng` | **위도, 경도 (WGS84)** |
| `useTime` | 이용 가능 시간 (자유 글, 예: `24시간 이용가능`) |
| `busiId`, `bnm`, `busiNm`, `busiCall` | 기관 ID, 기관명, 운영기관명, 운영기관 전화 |
| `stat` | 상태 (아래 코드표) |
| `statUpdDt` | 상태 갱신 시각 `yyyyMMddHHmmss` |
| `lastTsdt`, `lastTedt`, `nowTsdt` | 마지막 충전 시작·종료, 지금 충전 시작 (`yyyyMMddHHmmss`, 빈 값 가능) |
| `output` | 충전 용량 kW (`3`, `7`, `50`, `100`, `200` 등) |
| `method` | `단독` / `동시` |
| `powerType` | 전력 유형 (대부분 빈 값) |
| `zcode`, `zscode` | 시도, 시군구 코드 |
| `kind`, `kindDetail` | 충전소 구분 |
| `parkingFree` | 주차 무료 `Y` / 유료 `N` / 빈 값(현장 확인) |
| `note` | 충전소 안내 (예: 공사로 이용 불가) |
| `limitYn`, `limitDetail` | 이용자 제한 `Y`/`N`, 제한 사유 (예: 거주자 외 출입 제한) |
| `delYn`, `delDetail` | 삭제된 충전기 `Y` → 우리 DB에서 지움 |
| `trafficYn`, `year`, `floorNum`, `floorType`, `maker` | 편의 제공 여부, 설치 연도, 층, 지상(`F`)/지하(`B`), 제조사 (안 씀) |

```json
{"items": {"item": [
  {"statNm": "낙성대동주민센터", "statId": "ME174013", "chgerId": "01", "chgerType": "06",
   "addr": "서울특별시 관악구 낙성대로4가길 5", "addrDetail": "", "location": "",
   "lat": "37.476296", "lng": "126.9583876", "useTime": "24시간 이용가능",
   "busiId": "ME", "bnm": "기후에너지환경부", "busiNm": "기후에너지환경부", "busiCall": "1661-9408",
   "stat": "2", "statUpdDt": "20261105110259", "lastTsdt": "20261104231933", "lastTedt": "20261104235603", "nowTsdt": "",
   "powerType": "", "output": "50", "method": "단독", "zcode": "11", "zscode": "11620",
   "kind": "G0", "kindDetail": "G003", "parkingFree": "Y", "note": "",
   "limitYn": "N", "limitDetail": "", "delYn": "N", "delDetail": "",
   "trafficYn": "N", "year": "2017", "floorNum": "1", "floorType": "F", "maker": "시그넷"}
 ]},
 "resultCode": "00", "resultMsg": "NORMAL SERVICE.", "totalCount": 77047, "pageNo": 1, "numOfRows": 10}
```

**`stat` 충전기 상태**: `0` 알수없음, `1` 통신이상, `2` 충전대기(**사용 가능**), `3` 충전중, `4` 운영중지, `5` 점검중, `6` 예약중, `9` 상태미확인

**`chgerType` 충전기 타입**
| 코드 | 타입 | 코드 | 타입 |
|---|---|---|---|
| `01` | DC차데모 | `07` | AC3상 |
| `02` | AC완속 | `08` | DC콤보(완속) |
| `03` | DC차데모+AC3상 | `09` | NACS |
| `04` | DC콤보 | `10` | DC콤보+NACS |
| `05` | DC차데모+DC콤보 | `11` | DC콤보2(버스전용) |
| `06` | DC차데모+AC3상+DC콤보 | | |

급속/완속 구분: `02`(AC완속), `08`(DC콤보 완속)은 **완속**, 나머지는 급속으로 봄. `11`(버스전용)은 승용차 제안에서 제외.

**`zcode` 시도 코드** (전국 동기화 때 이 16개를 돌며 호출)
`11` 서울, `12` 전남광주통합특별시, `26` 부산, `27` 대구, `28` 인천, `30` 대전, `31` 울산, `36` 세종, `41` 경기, `43` 충북, `44` 충남, `47` 경북, `48` 경남, `50` 제주, `51` 강원, `52` 전북
(가이드 v1.25 기준 16개. 행정구역이 바뀌면 코드도 바뀌므로 가이드 갱신 확인)

**`kind` 충전소 구분**: `A0` 공공시설, `B0` 주차시설, `C0` 휴게시설, `D0` 관광시설, `E0` 상업시설, `F0` 차량정비시설, `G0` 기타시설, `H0` 공동주택시설, `I0` 근린생활시설, `J0` 교육문화시설.
`kindDetail` 예: `C001` **고속도로 휴게소**, `C002` 지방도로 휴게소, `B001` 공영주차장, `A002` 주민센터 (전체는 가이드 3.7)

### 3-2. 충전기 상태 `/getChargerStatus`

**Request**
| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `serviceKey` | string | 필수 | 인증키 |
| `pageNo` | int | 필수 | 페이지 번호 |
| `numOfRows` | int | 필수 | 최소 10, 최대 9999 |
| `dataType` | string | 필수 | `JSON` |
| `period` | int | 선택 | 최근 N분 안에 **상태가 바뀐 충전기만** (기본 5, 최소 1, 최대 10) |
| `zcode`, `zscode`, `kind`, `kindDetail`, `statId`, `chgerId` | string | 선택 | 3-1과 같음 |

```
GET https://apis.data.go.kr/B552584/EvCharger/getChargerStatus?serviceKey={DATA_GO_KR_KEY}&pageNo=1&numOfRows=9999&period=5&dataType=JSON
```

**Response** (`items.item[]`, 바뀐 충전기 1대당 1행. **좌표 없음** → 3-1과 `statId` + `chgerId`로 연결)
| 필드 | 설명 |
|---|---|
| `resultCode`, `resultMsg`, `totalCount`, `pageNo`, `numOfRows` | 3-1과 같음 |
| `busiId` | 기관 ID |
| `statId`, `chgerId` | 충전소 ID, 충전기 ID |
| `stat` | 상태 (3-1 코드표) |
| `statUpdDt` | 상태 갱신 시각 `yyyyMMddHHmmss` |
| `lastTsdt`, `lastTedt`, `nowTsdt` | 마지막 충전 시작·종료, 지금 충전 시작 |

```json
{"items": {"item": [
  {"busiId": "ME", "statId": "ME174085", "chgerId": "01", "stat": "3",
   "statUpdDt": "20261105110510", "lastTsdt": "20261105083039", "lastTedt": "20261105084720", "nowTsdt": "20261105103455"}
 ]},
 "resultCode": "00", "resultMsg": "NORMAL SERVICE.", "totalCount": 7189, "pageNo": 1, "numOfRows": 10}
```

**갱신 규칙**: 5분마다 `period=5`로 부름 (호출 간격과 `period`를 같게, 놓치는 일이 없게 하려면 `period`를 간격보다 1~2분 길게). 우리 DB에 없는 `statId`+`chgerId`는 무시하고 다음 날 3-1 동기화 때 들어옴

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
- **좌표가 KATEC**이라 서버에서 변환 필요: 차량 위치(WGS84) → KATEC으로 요청, 결과(KATEC) → WGS84로 지도 표시 (아래 "좌표 변환")
- 잘못된 키여도 오류 없이 빈 목록(`{"RESULT":{"OIL":[]}}`)이 옴 → 0장 표 참고
- **하루 300건** (가이드 기준. 2026-09-01에 1,500건에서 300건으로 줄었음) → 같은 위치 근처 요청은 서버에서 몇 분간 캐시. 시연·전시 기간에는 한도 증설 문의 필요
- 응답 JSON에 공백·줄바꿈이 많이 섞여 있지만 파싱에는 문제 없음

### 좌표 변환 (KATEC ↔ WGS84)
Java는 `org.locationtech.proj4j:proj4j` (+ `proj4j-epsg`)로 변환. KATEC 정의:
```
+proj=tmerc +lat_0=38 +lon_0=128 +k=0.9999 +x_0=400000 +y_0=600000 +ellps=bessel +units=m +no_defs
+towgs84=-115.80,474.99,674.11,1.16,-2.31,-1.63,6.43
```
검증값 (2026-11-05, proj4로 계산): 반포그린주유소 KATEC `(312740.19, 545022.52)` → WGS84 경도 `127.010870`, 위도 `37.503248`. 테스트 코드에 이 값을 넣어 변환이 맞는지 확인 (오차 10m 안)

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
- 한 점에서 가까운 순으로 검색하면 고속도로 위에서는 **휴게소 안 식당이 먼저** 나옴 (휴게소만 나오는 것은 아님)
- **맛집 기준 (2026-11-05 결정): "경로를 바꾸지 않고 들를 수 있는 곳".** 고속도로 구간은 진행 방향 앞 휴게소 안 식당, 일반도로 구간은 11번 경로선에서 200m 안 식당. 경로선 위 여러 점에서 검색하고 `id`로 중복 제거
- 한 번에 최대 45건이라, 경로 주변 넓은 범위는 여러 좌표로 나눠 호출

## 6. Kakao Maps JavaScript SDK (동승자 웹 지도)

- 키: `KAKAO_JS_KEY` (웹페이지에 노출되는 키. 등록 도메인에서만 동작)
- 등록 도메인: `http://localhost:8080`, `http://localhost:5173`. 배포 주소와 폰 테스트용 PC IP는 필요할 때 추가
- 카카오맵 사용 설정 ON 완료 (2026-11-04)
- 로드: `<script src="//dapi.kakao.com/v2/maps/sdk.js?appkey={KAKAO_JS_KEY}"></script>`

## 7. 전국주차장정보표준데이터 (목적지 근처 주차장)

> 지자체들이 올린 주차장 정보를 행안부 표준 양식으로 합친 데이터입니다. [데이터 페이지](https://www.data.go.kr/data/15012896/standard.do) (15012896). 반기마다 갱신.

| 항목 | 값 |
|---|---|
| URL | `https://api.data.go.kr/openapi/tn_pubr_prkplce_info_api` |
| 방식 | HTTPS GET |
| 포맷 | JSON, XML (`type`으로 선택) |
| 인증 | `DATA_GO_KR_KEY` (충전소와 같은 키). 2026-11-05 활용신청, 자동 승인. 개발계정 **하루 10,000건** |
| 용도 | 목적지 근처 주차장을 **가까운 순 / 싼 순**으로 고르기 (F9 목적지 근처 주차장). **DB 적재** |

### Request
| 파라미터 | 설명 |
|---|---|
| `serviceKey` | 필수. 인증키 |
| `pageNo`, `numOfRows` | 페이지. `numOfRows=1000`까지 확인 |
| `type` | `json` / `xml` |
| 응답 컬럼 이름 | 그대로 넣으면 **같은 값만 거름** (예: `prkplceSe=공영` → 18,051건). 좌표·반경 검색은 없음 |

```
GET /tn_pubr_prkplce_info_api?serviceKey={DATA_GO_KR_KEY}&type=json&pageNo=1&numOfRows=1000
```

### Response (`body.items.item[]`, 쓰는 것만)
| 필드 | 뜻 | 예 |
|---|---|---|
| `insttCode` + `prkplceNo` | 주차장 식별 (**둘을 합쳐야 유일**, 아래 참고) | `B552094`, `222-2-000003` |
| `prkplceNm` | 주차장명 | 와부 제5공영 |
| `prkplceSe` | 구분 | `공영`, `민영` |
| `prkplceType` | 유형 | `노외`, `노상`, `부설` |
| `rdnmadr`, `lnmadr` | 도로명 / 지번 주소 | |
| `latitude`, `longitude` | 위도, 경도 (WGS84, 문자열) | `37.54597782`, `127.2449375` |
| `prkcmprt` | 주차 구획 수 | `35` |
| `operDay` | 운영 요일 | `평일+토요일+공휴일` |
| `weekdayOperOpenHhmm` ~ `weekdayOperColseHhmm` | 평일 운영 시간 (필드명 오타 `Colse` 그대로) | `08:00` ~ `20:00` |
| `satOperOperOpenHhmm` ~ `satOperCloseHhmm` | 토요일 | |
| `holidayOperOpenHhmm` ~ `holidayCloseOpenHhmm` | 공휴일 (종료 필드명이 `holidayCloseOpenHhmm`) | |
| `parkingchrgeInfo` | 요금 구분 | `무료`, `유료`, `혼합` |
| `basicTime`, `basicCharge` | 기본 시간(분), 기본 요금(원) | `30`, `600` |
| `addUnitTime`, `addUnitCharge` | 추가 단위 시간(분), 추가 요금(원) | `10`, `300` |
| `dayCmmtktAdjTime`, `dayCmmtkt` | 1일권 적용 시간, 1일권 요금 | `4`, `7000` |
| `metpay` | 결제 방법 | `카드` |
| `phoneNumber` | 전화 | |
| `referenceDate` | 데이터 기준일 | `2026-04-23` |

숫자도 전부 **문자열**이고, 값이 없으면 빈 문자열(`""`)입니다. 응답 감싸는 구조: `header.resultCode`(`00` 정상), `body.items.item[]`, `body.totalCount`, `body.pageNo`, `body.numOfRows`. 마지막 페이지 = `ceil(totalCount / numOfRows)`

```json
{"header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE."},
 "body": {"items": {"item": [
   {"prkplceNo": "222-2-000003", "prkplceNm": "와부 제5공영", "prkplceSe": "공영", "prkplceType": "노외",
    "rdnmadr": "", "lnmadr": "경기도 남양주시 와부읍 팔당리 243-9", "prkcmprt": "35",
    "feedingSe": "1", "enforceSe": "5부제", "operDay": "평일+토요일+공휴일",
    "weekdayOperOpenHhmm": "08:00", "weekdayOperColseHhmm": "20:00",
    "satOperOperOpenHhmm": "08:00", "satOperCloseHhmm": "20:00",
    "holidayOperOpenHhmm": "08:00", "holidayCloseOpenHhmm": "20:00",
    "parkingchrgeInfo": "유료", "basicTime": "30", "basicCharge": "600",
    "addUnitTime": "10", "addUnitCharge": "300", "dayCmmtktAdjTime": "4", "dayCmmtkt": "7000",
    "monthCmmtkt": "70000", "metpay": "카드", "spcmnt": "",
    "institutionNm": "경기도 남양주도시공사 주차운영부", "phoneNumber": "031-560-1212",
    "latitude": "37.54597782", "longitude": "127.2449375", "pwdbsPpkZoneYn": "Y",
    "referenceDate": "2026-04-23", "insttCode": "B552094", "insttNm": "남양주도시공사"}
  ]},
  "numOfRows": 1, "pageNo": 1, "totalCount": 18994}}
```

안 쓰는 필드: `feedingSe`(급지), `enforceSe`(부제), `monthCmmtkt`(월정기권), `spcmnt`(특기사항), `pwdbsPpkZoneYn`(장애인 전용 구역), `institutionNm`·`insttNm`(관리기관).

### 확인한 사실 (2026-11-05, 전체 18,994건 받아서 확인)
- **공영 18,051 / 민영 943.** 민영은 거의 없음 → 사실상 공영주차장 데이터
- 요금: 무료 12,968, 유료 5,325, 혼합 700. 유료·혼합 6,026건 중 **기본 요금이 빈 것 515건, 추가 요금이 빈 것 1,239건** → "싼 순" 정렬에서 요금 없는 주차장은 맨 뒤로
- **좌표가 없거나 국내 범위를 벗어난 것 751건** (4%) → 적재할 때 제외
- **`prkplceNo`만으로는 유일하지 않음**: 같은 번호가 1,030개 있고, 그중 457개는 아예 다른 주차장 (예: `355-2-000029`가 구미도시공사와 구미시에 각각 다른 주차장). `insttCode + prkplceNo`로 구분
- 도로명 주소는 11,740건이 비어 있음 → 표시할 때 `rdnmadr`가 없으면 `lnmadr`
- **지역 편차가 큼**: 경기 2,865, 경북 2,042, 제주 1,657 … **서울 857**. 서울은 실제 주차장 수에 비해 매우 적음 (서울시는 서울 열린데이터광장에 따로 올림). 시연 목적지가 서울이면 보강 필요
- 기준일은 2026-04 ~ 2026-10 (기관마다 다름)
- curl은 충전소와 마찬가지로 User-Agent를 지정해서 호출함

### 설계 영향
- 반경 검색이 안 되고 전체가 1.9만 건(약 17MB)이라 **충전소처럼 DB 적재** 후 좌표로 근처를 찾음. 갱신은 반기 데이터라 **한 달에 1번** 전체 다시 받기로 충분 (19회 호출)
- 실시간 빈자리는 이 데이터에 없음 → 필요하면 [한국교통안전공단_주차정보 제공 API](https://data.go.kr/data/15099883/openapi.do) (15099883, 별도 활용신청). 제공처 안내상 실시간 정보는 시설 정보보다 건수가 적음

## 8. 서울시 공영주차장 (서울 보강)

> 7번 전국 데이터에 서울은 857건뿐이라 서울시 데이터로 보강합니다. [서울 열린데이터광장](https://data.seoul.go.kr/dataList/OA-13122/S/1/datasetView.do) (OA-13122). 공공데이터포털과 **다른 사이트, 다른 키**.

| 항목 | 값 |
|---|---|
| URL | `http://openapi.seoul.go.kr:8088/{KEY}/json/{SERVICE}/{START}/{END}/` (경로에 키가 들어감, 쿼리 파라미터 아님) |
| 인증 | `SEOUL_DATA_KEY` (2026-11-05 발급, 사용 URL은 임시로 localhost → 배포 주소 정해지면 수정). 샘플 키 `sample`은 5건까지만 |
| 한 번에 | 최대 1,000건 (`END - START < 1000`) |
| 서비스 | `GetParkInfo` (주차장 정보, 2,167행) / `GetParkingInfo` (시영주차장 실시간 대수, 122곳) |

⚠️ HTTP만 확인 (포트 8088). 서버 → 서울시 호출이라 Pleos의 HTTPS 조건(차·폰 ↔ 우리 서버)과는 상관없음.

### 8-1. `GetParkInfo` (주차장 정보)
**Request** (경로 방식. 순서가 정해져 있고, 앞의 선택값을 비우려면 공백 `%20`)
| 위치 | 이름 | 필수 | 설명 |
|---|---|---|---|
| 1 | `KEY` | 필수 | `SEOUL_DATA_KEY` |
| 2 | `TYPE` | 필수 | `json` (`xml`, `xls`도 됨) |
| 3 | `SERVICE` | 필수 | `GetParkInfo` |
| 4 | `START_INDEX` | 필수 | 시작 행 (1부터) |
| 5 | `END_INDEX` | 필수 | 끝 행. `END - START + 1 ≤ 1000` |
| 6 | `ADDR` | 선택 | 주소 일부 (예: `강남`) |
| 7 | `PKLT_CD` | 선택 | 주차장 코드 |
| 8 | `PRK_NOW_INFO_PVSN_YN` | 선택 | 실시간 연계 상태 `1`/`2`/`3`/`0` |

```
GET http://openapi.seoul.go.kr:8088/{SEOUL_DATA_KEY}/json/GetParkInfo/1/1000/
GET http://openapi.seoul.go.kr:8088/{SEOUL_DATA_KEY}/json/GetParkInfo/1001/2000/
GET http://openapi.seoul.go.kr:8088/{SEOUL_DATA_KEY}/json/GetParkInfo/1/5/강남
GET http://openapi.seoul.go.kr:8088/{SEOUL_DATA_KEY}/json/GetParkInfo/1/5/%20/1033125
```
전체 받기: 첫 호출의 `list_total_count`(2,167)까지 1,000건씩.

**Response** (`GetParkInfo.row[]`. 숫자 필드(`TPKCT`, `PRK_CRG` 등)는 **숫자**, 값이 없으면 `0.0`. 나머지는 문자열)

```json
{"GetParkInfo": {
  "list_total_count": 2167,
  "RESULT": {"CODE": "INFO-000", "MESSAGE": "정상 처리되었습니다"},
  "row": [
    {"PKLT_NM": "은평평화공원지하 공영주차장(구)", "ADDR": "은평구 녹번동 153-1", "PKLT_CD": "1032247",
     "PKLT_KND": "NW", "PKLT_KND_NM": "노외 주차장", "OPER_SE": "1", "OPER_SE_NM": "시간제 주차장",
     "TELNO": "02-350-5183", "PRK_NOW_INFO_PVSN_YN": "1",
     "PRK_NOW_INFO_PVSN_YN_NM": "현재~20분이내 연계데이터 존재(현재 주차대수 표현)",
     "TPKCT": 66.0, "CHGD_FREE_SE": "Y", "CHGD_FREE_NM": "유료",
     "NGHT_FREE_OPN_YN": "N", "NGHT_FREE_OPN_YN_NAME": "야간 미개방",
     "WD_OPER_BGNG_TM": "0000", "WD_OPER_END_TM": "2400", "WE_OPER_BGNG_TM": "0000", "WE_OPER_END_TM": "2400",
     "LHLDY_BGNG": "0000", "LHLDY": "2400", "LAST_DATA_SYNC_TM": "2025-06-19 18:25:17",
     "SAT_CHGD_FREE_SE": "N", "SAT_CHGD_FREE_NM": "무료", "LHLDY_YN": "N", "LHLDY_NM": "무료",
     "MNTL_CMUT_CRG": "120000", "CRB_PKLT_MNG_GROUP_NO": "",
     "PRK_CRG": 150.0, "PRK_HM": 5.0, "ADD_CRG": 150.0, "ADD_UNIT_TM_MNT": 5.0,
     "BUS_PRK_CRG": 0.0, "BUS_PRK_HM": 0.0, "BUS_PRK_ADD_HM": 0.0, "BUS_PRK_ADD_CRG": 0.0,
     "DLY_MAX_CRG": 0.0, "LAT": 0.0, "LOT": 0.0}
  ]}}
```
`LAT`·`LOT`가 `0.0`이면 좌표 없음 → 9번(카카오 주소 검색)으로 채움. 주소 앞에 `서울 `을 붙여서 검색.

| 필드 | 뜻 | 비고 |
|---|---|---|
| `PKLT_CD` | 주차장 코드 | **1곳에 여러 행**일 수 있음 (아래) |
| `PKLT_NM`, `ADDR` | 이름, 주소 | 주소는 "서울"이 빠진 구부터 (`성동구 마장동 463-2`) |
| `PKLT_KND` / `_NM` | `NW` 노외, `NS` 노상 | |
| `OPER_SE_NM` | 시간제 / 거주자 우선 / 버스전용 등 | 버스전용·거주자 전용은 제외 |
| `TPKCT` | 총 주차면 | |
| `CHGD_FREE_SE` | 유료 `Y` / 무료 `N` | 토요일·공휴일은 `SAT_CHGD_FREE_SE`, `LHLDY_YN` |
| `PRK_CRG`, `PRK_HM` | 기본 요금(원), 기본 시간(분) | 예: 5분 50원 |
| `ADD_CRG`, `ADD_UNIT_TM_MNT` | 추가 요금, 추가 단위 시간(분) | |
| `DLY_MAX_CRG` | 일 최대 요금 | |
| `WD_OPER_BGNG_TM` ~ `WD_OPER_END_TM` | 평일 운영 (`HHMM`, `2400`까지) | 주말 `WE_`, 공휴일 `LHLDY_BGNG` ~ `LHLDY` |
| `LAT`, `LOT` | 위도, 경도 | **노외는 대부분 비어 있음** (아래) |
| `PRK_NOW_INFO_PVSN_YN` | 실시간 연계 상태 | 이 서비스에는 현재 대수 컬럼이 없음 (삭제됨) |

### 8-2. `GetParkingInfo` (실시간 주차 대수)
| 필드 | 뜻 |
|---|---|
| `PKLT_CD` | 8-1과 같은 코드 (122곳 중 117곳이 8-1과 연결됨) |
| `TPKCT`, `NOW_PRK_VHCL_CNT` | 총 주차면, **현재 주차 대수** → 빈자리 = 차이 |
| `NOW_PRK_VHCL_UPDT_TM` | 갱신 시각 (호출 시점 기준 10분 전 값까지 확인) |
| `BSC_PRK_CRG`, `BSC_PRK_HR`, `ADD_PRK_CRG`, `ADD_PRK_HR`, `DAY_MAX_CRG` | 요금 (8-1과 이름이 다름) |

좌표 없음 → `PKLT_CD`로 8-1과 연결.

**Request**: 8-1과 같은 경로 방식, `SERVICE`만 `GetParkingInfo`. 선택값 없이 전체(122건)를 한 번에 받음
```
GET http://openapi.seoul.go.kr:8088/{SEOUL_DATA_KEY}/json/GetParkingInfo/1/1000/
```

**Response** (`GetParkingInfo.row[]`)
```json
{"GetParkingInfo": {
  "list_total_count": 122,
  "RESULT": {"CODE": "INFO-000", "MESSAGE": "정상 처리되었습니다"},
  "row": [
    {"PKLT_CD": "171721", "PKLT_NM": "세종로 공영주차장(시)", "ADDR": "종로구 세종로 80-1",
     "PKLT_TYPE": "NW", "PRK_TYPE_NM": "노외 주차장", "OPER_SE": "1", "OPER_SE_NM": "시간제 주차장",
     "TELNO": "02-2290-6566", "PRK_STTS_YN": "1", "PRK_STTS_NM": "현재~20분이내 연계데이터 존재(현재 주차대수 표현)",
     "TPKCT": 1260.0, "NOW_PRK_VHCL_CNT": 730.0, "NOW_PRK_VHCL_UPDT_TM": "2026-11-05 11:07:23",
     "PAY_YN": "Y", "PAY_YN_NM": "유료", "NGHT_PAY_YN": "N", "NGHT_PAY_YN_NM": "야간 미개방",
     "WD_OPER_BGNG_TM": "0000", "WD_OPER_END_TM": "2400", "WE_OPER_BGNG_TM": "0000", "WE_OPER_END_TM": "2400",
     "LHLDY_OPER_BGNG_TM": "0000", "LHLDY_OPER_END_TM": "2400",
     "SAT_CHGD_FREE_SE": "N", "SAT_CHGD_FREE_NM": "무료", "LHLDY_CHGD_FREE_SE": "N", "LHLDY_CHGD_FREE_SE_NAME": "무료",
     "PRD_AMT": "176000", "STRT_PKLT_MNG_NO": "",
     "BSC_PRK_CRG": 430.0, "BSC_PRK_HR": 5.0, "ADD_PRK_CRG": 430.0, "ADD_PRK_HR": 5.0,
     "BUS_BSC_PRK_CRG": 0.0, "BUS_BSC_PRK_HR": 0.0, "BUS_ADD_PRK_HR": 0.0, "BUS_ADD_PRK_CRG": 0.0,
     "DAY_MAX_CRG": 0.0, "SHRN_PKLT_MNG_NM": "*", "SHRN_PKLT_MNG_URL": "*", "SHRN_PKLT_YN": "N", "SHRN_PKLT_ETC": "*"}
  ]}}
```
- `PRK_STTS_YN`이 `1`(20분 이내)일 때만 `NOW_PRK_VHCL_CNT`를 믿고 표시. 빈자리 = `TPKCT - NOW_PRK_VHCL_CNT`
- `SHRN_*`(공유 주차장), `PRD_AMT`(정기권), `BUS_*`는 안 씀

### 확인한 사실 (2026-11-05, 전체 받아서 확인)
- 2,167행이지만 **주차장은 831곳**. 노상 주차장은 구간마다 행이 따로 있어서 같은 코드에 좌표가 다른 행이 여럿 (64곳)
- **좌표**: 노상 1,646행 중 1,400행은 있음. **노외 521행 중 468행은 없음.** 목적지 주차장으로 쓸 곳은 주로 노외라 문제 → **카카오 주소 검색으로 좌표 변환** (`KAKAO_REST_KEY`, 샘플 30곳 중 28곳 성공. 실패는 지번이 없어진 곳)
- 유료 2,093 / 무료 74. 유료인데 기본 요금이 없는 곳 9행뿐 → 요금 품질은 전국 데이터보다 좋음
- 실시간: 시영주차장 122곳, 오늘 갱신 109곳. **서울은 교통안전공단 API 없이도 일부 빈자리 표시 가능**
- **총 주차면 `TPKCT`가 1 이하인 곳 310곳** (831곳 중). 포털 댓글에서도 "더미 데이터냐"는 문의가 있음 → 총 주차면은 표시하지 않거나, 1 이하면 "정보 없음"
- 마지막 동기화(`LAST_DATA_SYNC_TM`)가 2025년 이전인 곳 342곳 → 오래된 정보일 수 있음
- 포털 댓글: 위경도가 "레이아웃에서 빠졌다"는 문의, 주차장 코드가 바뀌거나 가끔 안 나온다는 문의가 있음 → `PKLT_CD`가 영구 ID라고 믿지 말고, 동기화 때 **없어진 코드는 지우고 새 코드는 새로 넣는** 방식으로
- HTTPS는 지원 안 함 (포털 댓글 문의). 서버에서만 부르므로 문제 없음
- **전국 데이터(7번)와 겹침**: 이름으로 맞춰 보면 831곳 중 503곳이 전국 데이터 서울 857행에 있음 → 서울은 이 데이터를 기준으로 하고, 전국 데이터 서울 행은 **이름 + 100m 안**이면 중복으로 버림

## 9. Kakao Local 주소 검색 (주소 → 좌표 변환)

> 좌표가 없는 주차장(서울 노외 주차장 대부분)을 적재할 때 주소로 좌표를 채웁니다. [카카오 문서](https://developers.kakao.com/docs/latest/ko/local/dev-guide#address-coord). 5번(맛집)과 같은 REST 키.

| 항목 | 값 |
|---|---|
| URL | `https://dapi.kakao.com/v2/local/search/address.json` |
| 방식 | HTTPS GET |
| 인증 | 헤더 `Authorization: KakaoAK {KAKAO_REST_KEY}` |
| 용도 | `parking_lot` 적재 때만 (`coord_source = GEOCODE`). 사용자 요청 때는 부르지 않음 |

### Request
| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `query` | String | 필수 | 주소 (URL 인코딩). 서울시 데이터는 주소에 "서울"이 없으므로 앞에 붙여서 보냄 |
| `analyze_type` | String | 선택 | `similar`(기본, 비슷한 것도) / `exact`(정확히 일치) |
| `page` | Integer | 선택 | 1~45 (기본 1) |
| `size` | Integer | 선택 | 1~30 (기본 10) |

```
GET /v2/local/search/address.json?query=서울%20성동구%20마장동%20463-2
Authorization: KakaoAK {KAKAO_REST_KEY}
```

### Response
| 필드 | 타입 | 설명 |
|---|---|---|
| `meta.total_count`, `meta.pageable_count`, `meta.is_end` | | 전체 건수, 받을 수 있는 건수, 마지막 페이지 여부 |
| `documents[].address_name` | String | 전체 주소 |
| `documents[].address_type` | String | `REGION`(지명), `ROAD`(도로명), `REGION_ADDR`(지번 주소), `ROAD_ADDR`(도로명 주소) |
| `documents[].x`, `documents[].y` | String | **경도, 위도 (WGS84)** ← 이것만 씀 |
| `documents[].address` | Object | 지번 주소 상세 (`region_1~3depth_name`, `b_code`, `h_code`, `main_address_no`, `sub_address_no`, `mountain_yn`, `x`, `y`) |
| `documents[].road_address` | Object | 도로명 주소 상세 (`road_name`, `main_building_no`, `building_name`, `zone_no`(우편번호), `underground_yn`, `x`, `y`). 없으면 `null` |

```json
{"documents": [
  {"address_name": "서울 성동구 마장동 463-2", "address_type": "REGION_ADDR",
   "x": "127.035135249291", "y": "37.5699393404733",
   "address": {"address_name": "서울 성동구 마장동 463-2", "region_1depth_name": "서울",
     "region_2depth_name": "성동구", "region_3depth_name": "마장동", "region_3depth_h_name": "마장동",
     "b_code": "1120010500", "h_code": "1120054000", "main_address_no": "463", "sub_address_no": "2",
     "mountain_yn": "N", "x": "127.035135249291", "y": "37.5699393404733"},
   "road_address": {"address_name": "서울 성동구 청계천로10나길 38", "road_name": "청계천로10나길",
     "main_building_no": "38", "sub_building_no": "", "building_name": "마장동 공영주차장",
     "region_1depth_name": "서울", "region_2depth_name": "성동구", "region_3depth_name": "마장동",
     "underground_yn": "N", "zone_no": "04704", "x": "127.035132415559", "y": "37.5699311422586"}}
 ],
 "meta": {"is_end": true, "pageable_count": 1, "total_count": 1}}
```

### 확인한 사실 (2026-11-05)
- 서울 노외 주차장 중 좌표 없는 곳 샘플 30곳 → **28곳 변환 성공**. 실패 2곳은 지번이 없어진 주소 (`송파구 거여동 41-4` 등) → 적재에서 제외
- 결과가 여러 개면 첫 번째(`documents[0]`)를 씀
- Windows Git Bash에서 `curl --data-urlencode`로 한글을 보내면 인코딩이 깨져 **결과 0건**이 나옴. 서버(Java)나 Node에서는 정상. 테스트할 때 주의

## 10. 휴게소별 상품 월별 판매 상위 5 (파일 데이터)

> 한국도로공사가 매달 올리는 **실제 판매 순위** 파일입니다. API가 아니라 파일 다운로드. 공공데이터포털에는 "한국도로공사_휴게소별 상품 월별 판매 상위 5 매월"로 소개만 있고, 실제 다운로드는 [고속도로 공공데이터 포털](http://data.ex.co.kr/portal/fdwn/view?type=ETC&num=R1&requestfrom=dataset)

| 항목 | 값 |
|---|---|
| 제공 | 한국도로공사 (AI데이터처, 054-811-4418) |
| 형태 | **파일 (CSV)**. API 없음 |
| 갱신 | 매월 (포털 표기 "수시, 자동 갱신") |
| 받는 법 | 고속도로 공공데이터 포털 다운로드 화면에서 **연·월 선택** 후 다운로드. 이용 목적·연령대 등 입력 양식이 있어 **사람이 직접 받음** (자동화 안 함) |
| 이용 조건 | 무료, 이용허락범위 제한 없음 |
| 용도 | 휴게소 음식 Top5 (휴게소 음식 Top5) → `rest_area_top_item` |

⚠️ 파일 이름은 `.zip`이지만 **실제로는 gzip** (`gzip -d`로 풀어야 함). 안에 든 CSV는 **CP949** 인코딩.

### 컬럼 (`ETC_R1_05_02_*.csv`)
| 컬럼 | 예 | 설명 |
|---|---|---|
| `기준년월` | `2026-10` | |
| `휴게소내판매순위` | `1` ~ `5` | |
| `휴게소코드` | `S000001` | ⚠️ **도로공사 API의 `stdRestCd`·`restCd`와 다른 체계** (같은 경산(서울)휴게소가 API `S000048`, 이 파일 `S000418`) |
| `휴게소명` | `서울만남(부산)휴게소` | **이 이름으로 휴게소와 연결** |
| `매장코드`, `매장명` | `0025`, `말죽거리한식당` | 휴게소 안 매장 |
| `판매상품SEQ`, `판매상품명` | `1100000709`, `말죽거리소고기국밥` | 상품명에 `(할리스)` 같은 접두어가 붙은 경우가 있음 |

```
기준년월,휴게소내판매순위,휴게소코드,휴게소명,매장코드,매장명,판매상품SEQ,판매상품명
2026-10,1,S000001,서울만남(부산)휴게소,0025,말죽거리한식당,1100000709,말죽거리소고기국밥
2026-10,2,S000001,서울만남(부산)휴게소,0146,돈가스잔치,1100040400,(돈가스잔치)옛날돈가스
2026-10,3,S000001,서울만남(부산)휴게소,0133,효원당,1100035951,우리밀 호두과자(14개입)
```

### 확인한 사실 (2026-10월분, 1,332행)
- 휴게소 267곳 × 5위. 졸음쉼터·민자휴게소·"테스트휴게소"도 섞여 있음
- **편의점 포함 전체 순위**: 담배·생수가 순위에 있음 → 매장명으로 편의점(`편의점`, `CU`, `GS25`, `이마트24`, `세븐` 등)을 빼면 담배·생수는 모두 빠짐 (137행 제외, 1,195행 남음). 상품권으로 보이는 `하나)에코100000` 1건은 따로 제외
- 편의점을 빼면 **5개가 다 남는 휴게소 235곳**, 나머지 32곳은 1~4개
- **Top5 기준 (2026-11-05 결정): 편의점 매장을 뺀 먹거리 판매 순위 그대로.** 식당·카페(아메리카노)·간식(호두과자)을 따로 나누지 않음. 편의점을 빼서 순위가 비면 남은 것만 순서대로 1위부터 다시 매김
- 휴게소 연결: 이름으로 **196곳** 연결. 71곳은 1번 API 휴게소 목록(203곳)에 없음
- 가격: 0502 메뉴 이름과 맞으면 가격을 붙임 (편의점 제외 1,195행 중 406행)
- 포털에서 업종(음식점만) 필터로 받는 기능은 확인 못 함

## 11. 카카오모빌리티 길찾기 (경로선)

> Pleos SDK가 경로 좌표선(폴리라인)을 주지 않아서, **같은 출발지·목적지로 카카오 경로를 받아 "경로 주변"을 계산**합니다 (way-on.md 3.4). 5번과 같은 REST 키. [카카오모빌리티 문서](https://developers.kakaomobility.com/docs/navi-api/directions/)

| 항목 | 값 |
|---|---|
| URL | `https://apis-navi.kakaomobility.com/v1/directions` |
| 방식 | HTTPS GET |
| 인증 | 헤더 `Authorization: KakaoAK {KAKAO_REST_KEY}` (별도 신청 없이 호출됨, 2026-11-05 확인) |
| 용도 | 경로 주변 휴게소·충전소·맛집 (F2), 에너지 제안 (F6). **고속도로 주행 중인지** 판단 (도로 이름) |
| 호출 시점 | 세션 시작, **목적지·경유지가 바뀔 때**, 차가 경로에서 크게 벗어났을 때 (아래). 위치 보고마다 부르지 않음 |
| 한도 | 카카오 개발자 콘솔의 쿼터 화면에서 확인 필요 (아직 미확인) |

### Request
| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `origin` | string | 필수 | 출발지 `경도,위도` (차 현재 위치). 뒤에 `,angle=0~359`를 붙이면 진행 방향 반영 |
| `destination` | string | 필수 | 목적지 `경도,위도` |
| `waypoints` | string | 선택 | 경유지 `경도,위도` 최대 5개, `|`로 구분. 내비에 넣은 경유지와 같게 |
| `priority` | string | 선택 | `RECOMMEND`(기본) / `TIME` / `DISTANCE` |
| `summary` | boolean | 선택 | `true`면 요약만 (경로선 없음). 우리는 `false`(기본) |
| `alternatives`, `road_details`, `avoid`, `car_fuel`, `car_hipass` | | 선택 | 안 씀 |

```
GET /v1/directions?origin=127.042514,37.459939&destination=127.131800,37.076443
Authorization: KakaoAK {KAKAO_REST_KEY}
```

### Response
| 필드 | 설명 |
|---|---|
| `trans_id` | 요청 ID |
| `routes[0].result_code`, `result_msg` | `0` = 성공. 실패여도 **HTTP 200**에 다른 코드 (예: `104` 출발지·도착지가 5m 안) |
| `routes[0].summary.distance`, `duration` | 전체 거리(m), 시간(초) |
| `routes[0].summary.fare.toll` | 통행료(원) |
| `routes[0].summary.origin`, `destination`, `waypoints`, `bound` | 출발·도착·경유지 좌표, 경로를 감싸는 사각형 |
| `routes[0].sections[]` | 경유지로 나뉜 구간 (경유지 n개면 n+1개) |
| `sections[].roads[]` | 도로 구간. `name`(도로 이름, 예: `경부고속도로`), `distance`, `duration`, `traffic_state`, **`vertexes`**(경로선 좌표 `[경도, 위도, 경도, 위도, …]` 한 줄 배열) |
| `sections[].guides[]` | 안내 지점. `name`(예: `금토JCT`, `서울만남의광장휴게소`), `x`, `y`, `type`, `guidance`(예: "오른쪽 고속도로 진입"), `road_index` |

```json
{"trans_id": "...",
 "routes": [{
   "result_code": 0, "result_msg": "길찾기 성공",
   "summary": {"distance": 60662, "duration": 4049, "fare": {"taxi": 92100, "toll": 7500},
               "origin": {"name": "", "x": 127.0425, "y": 37.4599}, "destination": {"...": "..."}, "bound": {"...": "..."}},
   "sections": [{
     "distance": 60662, "duration": 4049,
     "roads": [{"name": "경부고속도로", "distance": 8, "duration": 4, "traffic_speed": 6, "traffic_state": 1,
                "vertexes": [127.04240532, 37.45988407, 127.04245133, 37.45982137]}],
     "guides": [{"name": "금토JCT", "x": 127.0832, "y": 37.4169, "distance": 6003, "duration": 386,
                 "type": 49, "guidance": "서판교 흥덕 방면으로 오른쪽 고속도로 진입", "road_index": 2}]
   }]
 }]}
```

`guides[].type` 예 (확인한 값): `100` 출발지, `1000` 경유지, `1` 좌회전, `2` 우회전, `6` 오른쪽 방향, `9` 오른쪽 고속도로 출구, `10` 고속도로 입구, `49` 오른쪽 고속도로 진입, `84` 톨게이트 진입

실패: 키가 틀리면 HTTP 401 `{"code":-401,"msg":"wrong appKey(...) format"}`. 경로를 못 찾으면 HTTP 200 + `result_code != 0`

### 확인한 사실 (2026-11-05)
- 기존 `KAKAO_REST_KEY`로 바로 동작 (서울만남의광장 → 안성휴게소: 60.7km, 67분, 경로선 좌표 663개, 도로 27구간)
- 경유지 1개 넣으면 `sections`가 2개로 나뉨
- 도로 이름(`roads[].name`)에 `고속도로`가 들어가면 그 구간은 고속도로 → **고속도로 구간에서는 휴게소 안 식당만, 일반도로 구간에서는 경로선 200m 안 식당** (way-on.md 8.1)
- ⚠️ **Pleos 내장 내비의 경로와 같다는 보장이 없음** (경로 엔진이 다름). 출발지·목적지·경유지를 같게 넣어 최대한 맞추고, 차 위치가 카카오 경로선에서 500m 넘게 벗어난 상태가 1분 이상 이어지면 현재 위치에서 다시 부름
