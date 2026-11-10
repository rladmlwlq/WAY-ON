import { useEffect, useState, type FormEvent } from 'react'
import { ApiError, getRestArea, searchPlaces, type RestAreaDetail, type SearchPlace } from './api'
import type { ProposePlace } from './useTripSocket'

// 주변 검색 (docs/app-api.md 3-10). 차 위치 근처를 카카오로 찾고, 고른 장소는
// 카카오맵 웹 링크(평점·영업시간은 거기서), 휴게소 안 매장이면 우리 데이터(메뉴·영업 중), [운전자에게 제안](6-1)

interface Props {
  token: string
  places: SearchPlace[] | null
  selected: SearchPlace | null
  onResults: (places: SearchPlace[] | null) => void
  onSelect: (place: SearchPlace | null) => void
  propose: (place: ProposePlace) => Promise<{ requestId: number }>
}

export function SearchPanel({ token, places, selected, onResults, onSelect, propose }: Props) {
  const [query, setQuery] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const q = query.trim()
    if (!q || busy) return
    setBusy(true)
    setError(null)
    onSelect(null)
    try {
      onResults((await searchPlaces(token, q)).places)
    } catch (err) {
      onResults(null)
      setError(err instanceof ApiError ? err.message : '검색하지 못했어요')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="card search">
      <form className="search-form" role="search" onSubmit={submit}>
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="차 근처 장소 검색 (예: 국밥, 카페)"
          aria-label="장소 검색"
          maxLength={50}
          enterKeyHint="search"
        />
        <button type="submit" className="primary" disabled={busy || !query.trim()}>
          {busy ? '찾는 중' : '검색'}
        </button>
      </form>
      {error && <p className="error">{error}</p>}

      {selected ? (
        // key: 다른 장소를 고르면 새로 그려서 이전 상태가 남지 않게
        <PlaceDetail key={selected.id} place={selected} onBack={() => onSelect(null)} propose={propose} />
      ) : (
        places && (
          <ol className="place-list" aria-label="검색 결과">
            {places.length === 0 && <li className="help">찾은 곳이 없어요. 다른 말로 검색해 보세요.</li>}
            {places.map((p, i) => (
              <li key={p.id}>
                <button type="button" className="place-row" onClick={() => onSelect(p)}>
                  <span className="pin-no" aria-hidden="true">
                    {i + 1}
                  </span>
                  <span className="place-main">
                    <strong>{p.name}</strong>
                    <span className="help">{[p.category, distance(p.distM)].filter(Boolean).join(' · ')}</span>
                  </span>
                  {p.restAreaId && <span className="badge">휴게소</span>}
                </button>
              </li>
            ))}
          </ol>
        )
      )}
    </section>
  )
}

interface DetailProps {
  place: SearchPlace
  onBack: () => void
  propose: (place: ProposePlace) => Promise<{ requestId: number }>
}

function PlaceDetail({ place, onBack, propose }: DetailProps) {
  const [restArea, setRestArea] = useState<RestAreaDetail | null>(null)
  const [sending, setSending] = useState(false)
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!place.restAreaId) return
    const mounted = { current: true }
    getRestArea(place.restAreaId)
      .then((r) => mounted.current && setRestArea(r))
      .catch(() => undefined) // 휴게소 정보는 덧붙임이라 실패하면 그냥 뺀다
    return () => {
      mounted.current = false
    }
  }, [place.restAreaId])

  const send = async () => {
    setSending(true)
    setError(null)
    try {
      await propose({ extId: place.id, name: place.name, lat: place.lat, lng: place.lng })
      setSent(true) // 진행 상황은 화면 위 요청 상태 카드(waypoint.status)가 보여 준다
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '보내지 못했어요')
    } finally {
      setSending(false)
    }
  }

  const open = restArea?.brands.filter((b) => b.openNow && b.name !== '기타').map((b) => b.name) ?? []

  return (
    <div className="place-detail">
      <button type="button" className="link-button" onClick={onBack}>
        ← 검색 결과
      </button>
      <div>
        <h2>{place.name}</h2>
        <p className="help">{[place.category, distance(place.distM)].filter(Boolean).join(' · ')}</p>
        {place.address && <p className="help">{place.address}</p>}
      </div>

      <button type="button" className="primary" onClick={send} disabled={sending || sent}>
        {sending ? '보내는 중…' : sent ? '운전자에게 보냈어요' : '운전자에게 경유지로 제안'}
      </button>
      {error && <p className="error">{error}</p>}

      <div className="place-actions">
        <a className="button secondary" href={place.url} target="_blank" rel="noopener noreferrer">
          카카오맵에서 보기
        </a>
        {place.phone && (
          <a className="button secondary" href={`tel:${place.phone}`}>
            전화
          </a>
        )}
      </div>
      <p className="help">평점·영업시간·메뉴 사진은 카카오맵에서 볼 수 있어요. 앱이 없으면 웹으로 열려요.</p>

      {restArea && (
        <div className="rest-area-part">
          <strong>{restArea.name} 안 매장이에요</strong>
          {restArea.topItems.length > 0 && (
            <ol className="menu-list">
              {restArea.topItems.slice(0, 3).map((item) => (
                <li key={item.rank}>
                  <span className="rank">{item.rank}위</span>
                  <span className="menu-name">{item.name}</span>
                  {item.price != null && <span className="price">{item.price.toLocaleString('ko-KR')}원</span>}
                </li>
              ))}
            </ol>
          )}
          <p className="help">지금 영업 중: {open.length > 0 ? open.slice(0, 6).join(' · ') : '정보 없음'}</p>
        </div>
      )}
    </div>
  )
}

function distance(m: number | null) {
  if (m == null) return null
  return m < 1000 ? `${m}m` : `${(m / 1000).toFixed(1)}km`
}
