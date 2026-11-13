package com.wayon.energy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wayon.trip.TripLiveService;

/** 오피넷 좌표 변환과 경로 앞쪽 검색 지점 */
class EnergyRulesTest {

	@Test
	void katec() {
		// api.md 4 검증값: 반포그린주유소 (오차 10m 안)
		double[] ll = Katec.toWgs84(312740.19, 545022.52);
		assertThat(EnergyService.meters(ll[0], ll[1], 37.503248, 127.010870)).isLessThan(10);
		double[] xy = Katec.fromWgs84(37.503248, 127.010870);
		assertThat(xy[0]).isCloseTo(312740.19, within(10.0));
		assertThat(xy[1]).isCloseTo(545022.52, within(10.0));
	}

	/** 북쪽으로 1km마다 점이 있는 직선 경로 (경도 127, 위도 37부터) */
	private static List<double[]> northPath(int km) {
		List<double[]> path = new ArrayList<>();
		for (int i = 0; i <= km; i++) {
			path.add(new double[] { 127.0, 37.0 + i / 111.0 });
		}
		return path;
	}

	@Test
	void pointsAheadEvery5kmWithinRange() {
		TripLiveService.Ahead ahead = new TripLiveService.Ahead(37.0, 127.0, northPath(40));
		List<double[]> points = EnergyService.points(ahead, 12_000);
		// 차 위치 + 5km + 10km (15km는 주행 가능 거리 밖)
		assertThat(points).hasSize(3);
		assertThat(EnergyService.meters(37.0, 127.0, points.get(2)[0], points.get(2)[1])).isBetween(9_500.0, 10_500.0);
	}

	@Test
	void pointsStartFromNearestVertexAndCapAtFour() {
		// 차가 경로 10km 지점에 있으면 그 앞쪽부터, 최대 4곳
		TripLiveService.Ahead ahead = new TripLiveService.Ahead(37.0 + 10 / 111.0, 127.0, northPath(60));
		List<double[]> points = EnergyService.points(ahead, 100_000);
		assertThat(points).hasSize(4);
		assertThat(points.get(1)[0]).isGreaterThan(37.0 + 14 / 111.0);
	}

	@Test
	void noPathMeansCarOnly() {
		assertThat(EnergyService.points(new TripLiveService.Ahead(37.0, 127.0, List.of()), 50_000)).hasSize(1);
	}
}
