import { useState } from 'react'
import { ApiError } from './api'
import type { ParkingPlace, ParkingSuggest, ProposePlace } from './useTripSocket'

// 목적지 근처 공영주차장 (docs/way-on.md 3.6, app-api 7장 parking.suggest).
// 차가 목적지 10km 또는 5분 안에 들어오면 서버가 한 번 보낸다. 가까운 순 / 싼 순으로 바꿔 보고,
// 고르면 운전자에게 "목적지를 이 주차장으로 바꿀까요?"를 보낸다 (경유지가 아니라 목적지, 주차 후 안내가 끝나게)

interface Props {
  suggest: ParkingSuggest
  propose: (place: ProposePlace) => Promise<{ requestId: number }>
  onClose: () => void
}

type Sort = 'near' | 'cheap'

export function ParkingCard({ suggest, propose, onClose }: Props) {
  const [sort, setSort] = useState<Sort>('near')
  const [selected, setSelected] = useState<ParkingPlace | null>(null)
  const [sending, setSending] = useState(false)
  const [sent, setSent] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const places = sorted(suggest.places, sort)

  const send = async (p: ParkingPlace) => {
    setSending(true)
    setError(null)
    try {
      await propose({ extId: p.extId, name: p.name, lat: p.lat, lng: p.lng })
      setSent(p.extId) // 진행 상황은 위 요청 상태 카드(waypoint.status)가 보여 준다
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '보내지 못했어요')
    } finally {
      setSending(false)
    }
  }

  return (
    <section className="card parking" aria-live="polite">
      <div className="parking-head">
        <div>
          <strong>🅿️ 목적지 근처 공영주차장</strong>
          <p className="help">{suggest.destName ? `${suggest.destName} 근처` : '목적지 근처'}</p>
        </div>
        <button type="button" className="link-button" onClick={onClose}>
          닫기
        </button>
      </div>

      {places.length === 0 ? (
        <p className="help">근처 2km 안에 공영주차장 정보가 없어요.</p>
      ) : selected ? (
        <div className="place-detail">
          <button type="button" className="link-button" onClick={() => setSelected(null)}>
            ← 주차장 목록
          </button>
          <div>
            <h2>{selected.name}</h2>
            <p className="help">{selected.address}</p>
            <p className="help">{facts(selected).join(' · ')}</p>
          </div>
          <button
            type="button"
            className="primary"
            onClick={() => send(selected)}
            disabled={sending || sent === selected.extId}
          >
            {sending ? '보내는 중…' : sent === selected.extId ? '운전자에게 보냈어요' : '운전자에게 목적지로 제안'}
          </button>
          <p className="help">수락하면 내비 목적지가 이 주차장으로 바뀌어요.</p>
          {error && <p className="error">{error}</p>}
          {selected.phone && (
            <div className="place-actions">
              <a className="button secondary" href={`tel:${selected.phone}`}>
                전화
              </a>
            </div>
          )}
        </div>
      ) : (
        <>
          <div className="sort-toggle" role="radiogroup" aria-label="정렬">
            {(['near', 'cheap'] as const).map((s) => (
              <button
                key={s}
                type="button"
                role="radio"
                aria-checked={sort === s}
                className={sort === s ? 'on' : ''}
                onClick={() => setSort(s)}
              >
                {s === 'near' ? '가까운 순' : '싼 순'}
              </button>
            ))}
          </div>
          <ul className="place-list">
            {places.map((p) => (
              <li key={p.extId}>
                <button type="button" className="place-row" onClick={() => setSelected(p)}>
                  <span className="place-main">
                    <strong>{p.name}</strong>
                    <span className="help">{facts(p).join(' · ')}</span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  )
}

/** 싼 순: 1시간 요금, 모르는 곳은 맨 뒤 (같으면 가까운 순) */
function sorted(places: ParkingPlace[], sort: Sort) {
  const list = [...places]
  if (sort === 'cheap') {
    list.sort((a, b) => (a.fee1h ?? Infinity) - (b.fee1h ?? Infinity) || a.distM - b.distM)
  } else {
    list.sort((a, b) => a.distM - b.distM)
  }
  return list
}

/** "350m · 1시간 1,200원 · 운영 중 · 빈자리 23" (아는 것만) */
function facts(p: ParkingPlace) {
  const parts = [p.distM < 1000 ? `${p.distM}m` : `${(p.distM / 1000).toFixed(1)}km`, fee(p)]
  if (p.openNow === true) parts.push('운영 중')
  else if (p.openNow === false) parts.push('운영 시간 아님')
  if (p.nowParked != null && p.totalSpaces != null) parts.push(`빈자리 ${Math.max(0, p.totalSpaces - p.nowParked)}`)
  else if (p.totalSpaces != null) parts.push(`${p.totalSpaces}면`)
  return parts
}

function fee(p: ParkingPlace) {
  if (p.feeType === 'FREE') return '무료'
  if (p.fee1h == null) return '요금 정보 없음'
  return `1시간 ${p.fee1h.toLocaleString('ko-KR')}원`
}
