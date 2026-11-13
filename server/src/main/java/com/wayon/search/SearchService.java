package com.wayon.search;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import com.wayon.common.ApiException;
import com.wayon.restarea.RestAreaService;
import com.wayon.trip.TripRows;
import com.wayon.trip.TripService;
import com.wayon.vehicle.VehicleService;

/**
 * 폰 검색 (app-api.md 3-10). 동승자 토큰이 있어야 하고 차 위치 근처를 먼저 찾는다.
 * 평점·영업시간은 API로 주지 않아 카카오맵 웹 링크로 보여 준다 (휴게소 안 매장은 우리 데이터로).
 * shortcut: 결과를 저장하지 않고 매번 부른다. 카카오 일 한도에 가까워지면 짧은 캐시 추가
 */
@Service
public class SearchService {

	private static final Logger log = LoggerFactory.getLogger(SearchService.class);
	static final int MAX_QUERY = 50;

	private final SearchClients clients;
	private final VehicleService vehicles;
	private final TripService trips;
	private final RestAreaService restAreas;

	public SearchService(SearchClients clients, VehicleService vehicles, TripService trips,
			RestAreaService restAreas) {
		this.clients = clients;
		this.vehicles = vehicles;
		this.trips = trips;
		this.restAreas = restAreas;
	}

	/** 3-10 */
	public SearchDto.Places select_places(String authorization, String query) {
		long vehicleId = vehicles.select_passengerVehicleId(authorization);
		String q = requireQuery(query);
		if (!clients.hasKakao()) {
			throw unavailable();
		}
		TripRows.CarPosition at = trips.select_carPosition(vehicleId);
		List<SearchClients.KakaoPlace> found;
		try {
			found = at == null ? clients.kakaoKeyword(q, null, null)
					: clients.kakaoKeyword(q, at.lat().doubleValue(), at.lng().doubleValue());
		} catch (RestClientException | NumberFormatException e) {
			log.warn("카카오 검색 실패: {}", e.getMessage());
			throw unavailable();
		}
		Map<String, String> inRestArea = restAreas
				.restAreaCodesByKakaoId(found.stream().map(SearchClients.KakaoPlace::id).toList());
		return new SearchDto.Places(found.stream().map(p -> new SearchDto.Place("kk:" + p.id(), p.placeName(),
				lastCategory(p.categoryName()), blankToNull(p.phone()),
				blankToNull(p.roadAddressName().isBlank() ? p.addressName() : p.roadAddressName()), p.lat(), p.lng(),
				p.distance(), https(p.placeUrl()), inRestArea.get(p.id()))).toList());
	}

	private static String requireQuery(String query) {
		String q = query == null ? "" : query.strip();
		if (q.isEmpty() || q.codePointCount(0, q.length()) > MAX_QUERY) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "검색어는 1~50자로 입력해 주세요");
		}
		return q;
	}

	/** "음식점 > 한식 > 국밥" → "국밥" */
	static String lastCategory(String category) {
		String[] parts = category.split(">");
		String last = parts[parts.length - 1].strip();
		return last.isEmpty() ? null : last;
	}

	/** 카카오는 http로 준다. 폰 웹이 https라 섞인 콘텐츠 경고를 피하려고 바꾼다 */
	static String https(String url) {
		return url != null && url.startsWith("http://") ? "https://" + url.substring("http://".length()) : url;
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s;
	}

	private static ApiException unavailable() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EXTERNAL_UNAVAILABLE", "잠시 뒤에 다시 시도해 주세요");
	}
}
