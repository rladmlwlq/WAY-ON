import { useEffect, useRef, useState } from 'react'
import type { SearchPlace } from './api'
import type { CarState, RouteUpdate } from './useTripSocket'

// 차 위치·경로선 지도 (docs/way-on.md 폰 지도, app-api.md 7장 trip.state·7-1 route.update).
// 카카오맵 JS SDK. 키는 VITE_KAKAO_JS_KEY, 등록된 도메인에서만 열린다 (api.md: localhost:5173, 배포 주소)

/* 쓰는 것만 적은 카카오맵 타입 */
interface LatLng {
  getLat(): number
}
interface KMap {
  setBounds(bounds: LatLngBounds, top?: number, right?: number, bottom?: number, left?: number): void
  setCenter(latlng: LatLng): void
  setLevel(level: number): void
}
interface LatLngBounds {
  extend(latlng: LatLng): void
}
interface Layer {
  setMap(map: KMap | null): void
}
interface Overlay extends Layer {
  setPosition(latlng: LatLng): void
}
interface KakaoMaps {
  load(cb: () => void): void
  LatLng: new (lat: number, lng: number) => LatLng
  LatLngBounds: new () => LatLngBounds
  Map: new (el: HTMLElement, options: { center: LatLng; level: number }) => KMap
  Polyline: new (options: Record<string, unknown>) => Layer
  CustomOverlay: new (options: { position: LatLng; content: HTMLElement; yAnchor?: number; zIndex?: number }) => Overlay
}
declare global {
  interface Window {
    kakao?: { maps: KakaoMaps }
  }
}

let sdk: Promise<KakaoMaps> | null = null

function loadKakao(): Promise<KakaoMaps> {
  const key = import.meta.env.VITE_KAKAO_JS_KEY as string | undefined
  if (!key) return Promise.reject(new Error('VITE_KAKAO_JS_KEY 없음'))
  sdk ??= new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(key)}&autoload=false`
    script.onload = () => window.kakao?.maps.load(() => resolve(window.kakao!.maps))
    script.onerror = () => {
      sdk = null // 다음에 다시 시도
      reject(new Error('카카오맵을 불러오지 못함'))
    }
    document.head.append(script)
  })
  return sdk
}

interface Props {
  carState: CarState | null
  route: RouteUpdate | null
  /** 검색 결과 핀 (3-10). 누르면 onSelect */
  places?: SearchPlace[] | null
  selectedId?: string | null
  onSelect?: (place: SearchPlace) => void
}

export function TripMap({ carState, route, places, selectedId, onSelect }: Props) {
  const box = useRef<HTMLDivElement>(null)
  const [kakao, setKakao] = useState<{ maps: KakaoMaps; map: KMap } | null>(null)
  const [failed, setFailed] = useState(false)
  const car = useRef<{ overlay: Overlay; arrow: HTMLElement } | null>(null)
  const routeLayers = useRef<Layer[]>([])

  useEffect(() => {
    const mounted = { current: true }
    loadKakao()
      .then((maps) => {
        if (!mounted.current || !box.current) return
        // 처음엔 남한 전체. 경로나 차 위치가 오면 맞춘다
        const map = new maps.Map(box.current, { center: new maps.LatLng(36.4, 127.8), level: 12 })
        setKakao({ maps, map })
      })
      .catch(() => mounted.current && setFailed(true))
    return () => {
      mounted.current = false
    }
  }, [])

  // 경로선·목적지·경유지. 새 경로가 오면 경로 전체가 보이게 맞춘다
  useEffect(() => {
    if (!kakao) return
    const { maps, map } = kakao
    routeLayers.current.forEach((layer) => layer.setMap(null))
    routeLayers.current = []
    if (!route || !route.destination) return

    const path = route.path.map(([lng, lat]) => new maps.LatLng(lat, lng))
    if (path.length > 1) {
      const line = new maps.Polyline({
        path,
        strokeWeight: 6,
        strokeColor: '#2b6cf6',
        strokeOpacity: 0.85,
        strokeStyle: route.source === 'APPROX' ? 'shortdash' : 'solid',
      })
      line.setMap(map)
      routeLayers.current.push(line)
    }
    const pins = [
      ...route.waypoints.map((w, i) => ({ place: w, label: String(i + 1), kind: 'waypoint' })),
      { place: route.destination, label: '도착', kind: 'dest' },
    ]
    for (const { place, label, kind } of pins) {
      const el = document.createElement('div')
      el.className = `map-pin ${kind}`
      el.textContent = label
      el.title = place.name
      const pin = new maps.CustomOverlay({ position: new maps.LatLng(place.lat, place.lng), content: el, yAnchor: 1.1, zIndex: 2 })
      pin.setMap(map)
      routeLayers.current.push(pin)
    }

    const bounds = new maps.LatLngBounds()
    path.forEach((p) => bounds.extend(p))
    pins.forEach(({ place }) => bounds.extend(new maps.LatLng(place.lat, place.lng)))
    map.setBounds(bounds, 32, 32, 32, 32)
    // route 객체가 바뀔 때만 (버전이 같으면 useTripSocket이 같은 객체를 돌려준다)
  }, [kakao, route])

  // 검색 결과 핀. 새 결과면 결과와 차가 다 보이게, 고르면 그 핀을 가운데로
  const placeLayers = useRef<Layer[]>([])
  const select = useRef(onSelect)
  select.current = onSelect
  useEffect(() => {
    if (!kakao) return
    const { maps, map } = kakao
    placeLayers.current.forEach((layer) => layer.setMap(null))
    placeLayers.current = []
    if (!places || places.length === 0) return
    places.forEach((p, i) => {
      const el = document.createElement('button')
      el.type = 'button'
      el.className = `map-pin place${p.id === selectedId ? ' selected' : ''}`
      el.textContent = String(i + 1)
      el.setAttribute('aria-label', `${i + 1}번 ${p.name}`)
      el.onclick = () => select.current?.(p)
      const pin = new maps.CustomOverlay({ position: new maps.LatLng(p.lat, p.lng), content: el, yAnchor: 1.1, zIndex: p.id === selectedId ? 5 : 4 })
      pin.setMap(map)
      placeLayers.current.push(pin)
    })
    const chosen = places.find((p) => p.id === selectedId)
    if (chosen) {
      map.setCenter(new maps.LatLng(chosen.lat, chosen.lng))
      return
    }
    const bounds = new maps.LatLngBounds()
    places.forEach((p) => bounds.extend(new maps.LatLng(p.lat, p.lng)))
    if (carState) bounds.extend(new maps.LatLng(carState.car.lat, carState.car.lng))
    map.setBounds(bounds, 32, 32, 32, 32)
    // carState는 위치가 올 때마다 바뀌어서 빼야 지도가 5초마다 다시 맞춰지지 않는다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kakao, places, selectedId])

  // 차 위치: 진행 방향으로 돌린 화살표. 경로가 없으면 차를 가운데에
  useEffect(() => {
    if (!kakao || !carState) return
    const { maps, map } = kakao
    const at = new maps.LatLng(carState.car.lat, carState.car.lng)
    if (!car.current) {
      const el = document.createElement('div')
      el.className = 'car-marker'
      el.setAttribute('aria-label', '차 위치')
      const arrow = document.createElement('span')
      el.append(arrow)
      const overlay = new maps.CustomOverlay({ position: at, content: el, zIndex: 3 })
      overlay.setMap(map)
      car.current = { overlay, arrow }
      if (!route?.destination) map.setLevel(5) // 처음 위치: 남한 전체 → 동네 크기
    }
    car.current.overlay.setPosition(at)
    car.current.arrow.style.transform = `rotate(${carState.car.heading ?? 0}deg)`
    // 검색 결과를 보는 중이면 차를 따라가지 않는다
    if (!route?.destination && !places?.length) map.setCenter(at)
  }, [kakao, carState, route, places])

  if (failed) {
    return <p className="help">지도를 불러오지 못했어요. 차와 연결은 그대로 유지돼요.</p>
  }
  return <div ref={box} className="trip-map" role="img" aria-label="차 위치와 경로 지도" />
}
