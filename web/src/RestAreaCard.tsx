import type { RestAreaInfo } from './api'

// 휴게소 안내 카드 (docs/way-on.md 3.8, app-api.md 7장 restarea.info).
// 차가 경유지로 넣은 휴게소에 2km 안으로 들어오면(APPROACH) 뜨고, 도착하면(ARRIVED) 제목만 바뀐다. 떠나면 닫힌다.
// 차 화면은 지도, 폰은 고르기 쉬운 목록: 인기 메뉴 → 지금 영업 중인 매장 → 편의시설

const TOP_MENUS = 3
const OPEN_STORES = 6

export function RestAreaCard({ info }: { info: RestAreaInfo }) {
  const ra = info.restArea
  const arrived = info.phase === 'ARRIVED'
  // 도로공사 매장 데이터의 "기타"는 매장 이름이 아니라 분류라서 뺀다
  const open = ra.brands.filter((b) => b.openNow && b.name !== '기타').map((b) => b.name)
  const facilities = [...new Set(ra.facilities.map((f) => f.name))]

  return (
    <section className="card rest-area" aria-live="polite">
      <div>
        <span className={`badge ${arrived ? 'ok' : ''}`}>{arrived ? '도착했어요' : '곧 도착해요'}</span>
        <h2>{ra.name}</h2>
        <p className="help">
          {ra.routeName} · {ra.direction}
        </p>
      </div>

      {ra.topItems.length > 0 && (
        <div className="rest-area-part">
          <strong>인기 메뉴{ra.topMonth ? ` (${ra.topMonth} 판매 순위)` : ''}</strong>
          <ol className="menu-list">
            {ra.topItems.slice(0, TOP_MENUS).map((item) => (
              <li key={item.rank}>
                <span className="rank">{item.rank}위</span>
                <span className="menu-name">
                  {item.name}
                  {item.store && <span className="help">{item.store}</span>}
                </span>
                {item.price != null && <span className="price">{item.price.toLocaleString('ko-KR')}원</span>}
              </li>
            ))}
          </ol>
        </div>
      )}

      <div className="rest-area-part">
        <strong>지금 영업 중</strong>
        {open.length > 0 ? (
          <p>
            {open.slice(0, OPEN_STORES).join(' · ')}
            {open.length > OPEN_STORES && <span className="help"> 외 {open.length - OPEN_STORES}곳</span>}
          </p>
        ) : (
          <p className="help">영업 중인 매장 정보가 없어요</p>
        )}
      </div>

      {facilities.length > 0 && (
        <div className="rest-area-part">
          <strong>편의시설</strong>
          <ul className="chips">
            {facilities.map((name) => (
              <li key={name}>{name}</li>
            ))}
          </ul>
        </div>
      )}
    </section>
  )
}
