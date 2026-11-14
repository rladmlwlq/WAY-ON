import { useState } from 'react'
import { ApiError } from './api'
import type { EnergyPlace, EnergySuggest, ProposePlace } from './useTripSocket'

// 에너지 제안 (docs/way-on.md 3.10, app-api 7장 energy.suggest).
// 주행 가능 거리가 목적지까지 남은 거리 + 20km보다 짧으면 서버가 한 번 보낸다.
// 경로 앞쪽, 주행 가능 거리 안의 충전소(전기차)·주유소(연료차)를 보여 주고, 고르면 운전자에게 경유지로 제안

interface Props {
  suggest: EnergySuggest
  propose: (place: ProposePlace) => Promise<{ requestId: number }>
  onClose: () => void
}

export function EnergyCard({ suggest, propose, onClose }: Props) {
  const [selected, setSelected] = useState<EnergyPlace | null>(null)
  const [sending, setSending] = useState(false)
  const [sent, setSent] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const ev = suggest.energyType === 'EV'
  const what = ev ? '충전소' : '주유소'

  const send = async (p: EnergyPlace) => {
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
    <section className="card energy" aria-live="polite">
      <div className="parking-head">
        <div>
          <strong>{ev ? '🔋 배터리가 목적지까지 부족할 수 있어요' : '⛽ 연료가 목적지까지 부족할 수 있어요'}</strong>
          <p className="help">
            {[suggest.remainDistM != null ? `목적지까지 ${km(suggest.remainDistM)}` : null, `주행 가능 ${km(suggest.rangeM)}`]
              .filter(Boolean)
              .join(' · ')}
          </p>
        </div>
        <button type="button" className="link-button" onClick={onClose}>
          닫기
        </button>
      </div>

      {suggest.places.length === 0 ? (
        <p className="help">주행 가능 거리 안에서 {what}를 찾지 못했어요. 검색창에서 직접 찾아 주세요.</p>
      ) : selected ? (
        <div className="place-detail">
          <button type="button" className="link-button" onClick={() => setSelected(null)}>
            ← {what} 목록
          </button>
          <div>
            <h2>{selected.name}</h2>
            {selected.address && <p className="help">{selected.address}</p>}
            <p className="help">{facts(selected).join(' · ')}</p>
          </div>
          <button
            type="button"
            className="primary"
            onClick={() => send(selected)}
            disabled={sending || sent === selected.extId}
          >
            {sending ? '보내는 중…' : sent === selected.extId ? '운전자에게 보냈어요' : '운전자에게 경유지로 제안'}
          </button>
          {error && <p className="error">{error}</p>}
          {selected.detail.phone && (
            <div className="place-actions">
              <a className="button secondary" href={`tel:${selected.detail.phone}`}>
                전화
              </a>
            </div>
          )}
        </div>
      ) : (
        <>
          <p className="help">경로 앞쪽, 지금 주행 가능 거리 안에 있는 {what}예요.</p>
          <ul className="place-list">
            {suggest.places.map((p) => (
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

/** "3.2km · 휘발유 1,689원" / "1.4km · 전기차 충전소" */
function facts(p: EnergyPlace) {
  const parts = [km(p.distM)]
  if (p.detail.gasoline) parts.push(`휘발유 ${p.detail.gasoline.toLocaleString('ko-KR')}원`)
  else if (p.detail.category) parts.push(p.detail.category)
  return parts
}

function km(m: number) {
  return m < 1000 ? `${m}m` : `${(m / 1000).toFixed(1)}km`
}
