package com.wayon.restarea;

import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 휴게소 기준 데이터 읽기 (SQL은 resources/mapper/RestAreaMapper.xml). 인자는 도로공사 휴게소 코드 */
@Mapper
public interface RestAreaMapper {

	RestAreaRows.RestArea findByCode(@Param("code") String code);

	List<RestAreaRows.RestArea> findAll();

	List<RestAreaRows.RestArea> findByCodes(@Param("codes") Collection<String> codes);

	/** 휴게소마다 가장 최근 달의 Top5 */
	List<RestAreaRows.TopItem> findLatestTopItems(@Param("codes") Collection<String> codes);

	List<RestAreaRows.Brand> findBrands(@Param("code") String code);

	List<RestAreaRows.Facility> findFacilities(@Param("codes") Collection<String> codes);
}
