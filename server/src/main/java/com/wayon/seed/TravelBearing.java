package com.wayon.seed;

import java.util.Map;

/**
 * 휴게소에 들어가는 차의 진행 방향(0~359도, 북=0, 시계 방향).
 * 도로공사 API에 없어서, 휴게소에서 방향 도시(이름 괄호 안, 예: "부산") 쪽으로 향하는 방위각으로 정한다.
 * 상·하행 휴게소 위치로 구하는 방법은 두 휴게소가 길을 따라 앞뒤로 어긋나 있어 맞지 않았다 (2026-11-05 확인).
 * 노선이 크게 휘는 곳은 틀릴 수 있으므로 시연 구간은 rest_area_bearing_override.csv로 덮어쓴다.
 */
public final class TravelBearing {

	/** 방향 도시 좌표 (위도, 경도). 시청·IC 근처 대략값이면 충분하다. */
	static final Map<String, double[]> CITIES = Map.ofEntries(
			Map.entry("서울", new double[] { 37.566, 126.978 }),
			Map.entry("부산", new double[] { 35.115, 129.041 }),
			Map.entry("서부산", new double[] { 35.150, 128.950 }),
			Map.entry("인천", new double[] { 37.456, 126.705 }),
			Map.entry("대구", new double[] { 35.871, 128.601 }),
			Map.entry("대전", new double[] { 36.351, 127.385 }),
			Map.entry("광주", new double[] { 35.160, 126.851 }),
			Map.entry("목포", new double[] { 34.811, 126.392 }),
			Map.entry("순천", new double[] { 34.950, 127.487 }),
			Map.entry("광양", new double[] { 34.940, 127.696 }),
			Map.entry("영암", new double[] { 34.800, 126.700 }),
			Map.entry("무안", new double[] { 34.990, 126.480 }),
			Map.entry("전주", new double[] { 35.824, 127.148 }),
			Map.entry("완주", new double[] { 35.904, 127.162 }),
			Map.entry("논산", new double[] { 36.187, 127.099 }),
			Map.entry("공주", new double[] { 36.446, 127.119 }),
			Map.entry("서천", new double[] { 36.080, 126.690 }),
			Map.entry("당진", new double[] { 36.890, 126.646 }),
			Map.entry("천안", new double[] { 36.815, 127.114 }),
			Map.entry("평택", new double[] { 36.992, 127.113 }),
			Map.entry("시흥", new double[] { 37.380, 126.803 }),
			Map.entry("일산", new double[] { 37.658, 126.770 }),
			Map.entry("하남", new double[] { 37.539, 127.215 }),
			Map.entry("퇴계원", new double[] { 37.646, 127.143 }),
			Map.entry("양평", new double[] { 37.492, 127.488 }),
			Map.entry("춘천", new double[] { 37.881, 127.730 }),
			Map.entry("양양", new double[] { 38.075, 128.619 }),
			Map.entry("속초", new double[] { 38.207, 128.592 }),
			Map.entry("강릉", new double[] { 37.752, 128.876 }),
			Map.entry("동해", new double[] { 37.525, 129.114 }),
			Map.entry("삼척", new double[] { 37.450, 129.165 }),
			Map.entry("제천", new double[] { 37.133, 128.191 }),
			Map.entry("청주", new double[] { 36.642, 127.489 }),
			Map.entry("청원", new double[] { 36.585, 127.430 }),
			Map.entry("남이", new double[] { 36.530, 127.420 }),
			Map.entry("상주", new double[] { 36.411, 128.159 }),
			Map.entry("영덕", new double[] { 36.415, 129.366 }),
			Map.entry("포항", new double[] { 36.019, 129.343 }),
			Map.entry("현풍", new double[] { 35.694, 128.447 }),
			Map.entry("창원", new double[] { 35.228, 128.681 }),
			Map.entry("마산", new double[] { 35.214, 128.581 }),
			Map.entry("통영", new double[] { 34.854, 128.433 }),
			Map.entry("냉정", new double[] { 35.250, 128.850 }));

	private TravelBearing() {
	}

	/** 방향 도시 쪽 방위각. 도시를 모르면 null (양방향 휴게소이거나 좌표표에 없는 도시) */
	public static Integer toward(String directionCity, double lat, double lng) {
		if (directionCity == null) {
			return null;
		}
		double[] city = CITIES.get(directionCity);
		if (city == null) {
			return null;
		}
		return (int) Math.round(bearing(lat, lng, city[0], city[1])) % 360;
	}

	/** 두 좌표 사이 초기 방위각 (도) */
	static double bearing(double lat1, double lng1, double lat2, double lng2) {
		double f1 = Math.toRadians(lat1);
		double f2 = Math.toRadians(lat2);
		double dl = Math.toRadians(lng2 - lng1);
		double y = Math.sin(dl) * Math.cos(f2);
		double x = Math.cos(f1) * Math.sin(f2) - Math.sin(f1) * Math.cos(f2) * Math.cos(dl);
		return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
	}
}
