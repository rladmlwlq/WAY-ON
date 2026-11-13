package com.wayon.energy;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/** 오피넷 좌표계 KATEC ↔ WGS84 (api.md 4 좌표 변환). 검증값: 반포그린주유소 (312740.19, 545022.52) ↔ (37.503248, 127.010870) */
final class Katec {

	private static final CoordinateTransform TO_KATEC;
	private static final CoordinateTransform TO_WGS84;

	static {
		CRSFactory crs = new CRSFactory();
		CoordinateReferenceSystem wgs84 = crs.createFromParameters("WGS84", "+proj=longlat +datum=WGS84 +no_defs");
		CoordinateReferenceSystem katec = crs.createFromParameters("KATEC",
				"+proj=tmerc +lat_0=38 +lon_0=128 +k=0.9999 +x_0=400000 +y_0=600000 +ellps=bessel +units=m +no_defs"
						+ " +towgs84=-115.80,474.99,674.11,1.16,-2.31,-1.63,6.43");
		CoordinateTransformFactory f = new CoordinateTransformFactory();
		TO_KATEC = f.createTransform(wgs84, katec);
		TO_WGS84 = f.createTransform(katec, wgs84);
	}

	private Katec() {
	}

	/** (위도, 경도) → {x, y} */
	static double[] fromWgs84(double lat, double lng) {
		ProjCoordinate out = new ProjCoordinate();
		synchronized (TO_KATEC) {
			TO_KATEC.transform(new ProjCoordinate(lng, lat), out);
		}
		return new double[] { out.x, out.y };
	}

	/** {x, y} → {위도, 경도} */
	static double[] toWgs84(double x, double y) {
		ProjCoordinate out = new ProjCoordinate();
		synchronized (TO_WGS84) {
			TO_WGS84.transform(new ProjCoordinate(x, y), out);
		}
		return new double[] { out.y, out.x };
	}
}
