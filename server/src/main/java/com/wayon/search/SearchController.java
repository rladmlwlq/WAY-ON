package com.wayon.search;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** app-api.md 3-10 REST: 폰 웹 검색 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

	private final SearchService service;

	public SearchController(SearchService service) {
		this.service = service;
	}

	@GetMapping("/places")
	public SearchDto.Places select_places(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@RequestParam(value = "query", required = false) String query) {
		return service.select_places(authorization, query);
	}
}
