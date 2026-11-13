package com.wayon.trip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class KakaoDirectionsTest {

	@Test
	void 경로선은_약_20m_간격으로_줄이고_처음과_끝은_남긴다() {
		// 경도 0.00001도 ≈ 0.9m (위도 37도). 1m 간격 점 101개 → 100m
		List<double[]> points = new ArrayList<>();
		for (int i = 0; i <= 100; i++) {
			points.add(new double[] { 127.0 + i * 0.0000112, 37.0 });
		}
		List<double[]> thin = KakaoDirections.thin(points);
		assertArrayEquals(points.get(0), thin.get(0));
		assertArrayEquals(points.get(100), thin.get(thin.size() - 1));
		assertEquals(6, thin.size()); // 0, 20, 40, 60, 80m + 끝
	}

	@Test
	void 점이_하나거나_없어도_된다() {
		assertEquals(0, KakaoDirections.thin(List.of()).size());
		assertEquals(1, KakaoDirections.thin(List.<double[]>of(new double[] { 127, 37 })).size());
	}
}
