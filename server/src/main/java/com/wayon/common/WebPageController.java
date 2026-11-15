package com.wayon.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// 폰 웹을 서버가 같이 내보낼 때(-Pweb) QR 주소 /join#코드 도 웹 첫 화면으로. 웹은 주소로 화면을 나눈다 (web/src/App.tsx)
@Controller
public class WebPageController {

	@GetMapping("/join")
	public String forward_join() {
		return "forward:/index.html";
	}

}
