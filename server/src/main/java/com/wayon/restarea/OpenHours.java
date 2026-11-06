package com.wayon.restarea;

import java.time.LocalTime;

/**
 * 매장 영업 중 판단 (app-api.md 3-7, way-on.md 3.8). 시각은 한국 시간 "HH:mm", 끝은 "24:00"까지.
 *
 * <ul>
 * <li>끝이 시작보다 이르면 다음 날까지 (22:00~02:00)</li>
 * <li>시작과 끝이 같으면 하루 종일로 본다 (데이터에 07:00~07:00 같은 값이 있음)</li>
 * <li>값이 없거나 형식이 틀리면 null (모름)</li>
 * </ul>
 */
final class OpenHours {

	private OpenHours() {
	}

	static Boolean isOpen(String open, String close, LocalTime now) {
		Integer from = minutes(open);
		Integer to = minutes(close);
		if (from == null || to == null) {
			return null;
		}
		int t = now.getHour() * 60 + now.getMinute();
		if (from.equals(to)) {
			return true;
		}
		if (from < to) {
			return from <= t && t < to;
		}
		return t >= from || t < to;
	}

	/** "07:30" → 450, "24:00" → 1440 */
	static Integer minutes(String hhmm) {
		if (hhmm == null || !hhmm.matches("\\d{2}:\\d{2}")) {
			return null;
		}
		int h = Integer.parseInt(hhmm.substring(0, 2));
		int m = Integer.parseInt(hhmm.substring(3));
		if (h > 24 || m > 59 || (h == 24 && m > 0)) {
			return null;
		}
		return h * 60 + m;
	}
}
