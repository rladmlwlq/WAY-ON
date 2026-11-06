package com.wayon.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 기준 데이터 적재를 실행하고 서버를 끈다. 평소 서버 실행에는 끼어들지 않는다.
 *
 * <pre>
 * gradlew bootRun --args="--wayon.seed=rest-area-load"    CSV → 로컬 DB (키 필요 없음)
 * gradlew bootRun --args="--wayon.seed=rest-area-fetch"   도로공사 API → CSV → 로컬 DB (EX_API_KEY 필요)
 * gradlew bootRun --args="--wayon.seed=top5-import --wayon.seed.file=D:/.../판매순위.zip"   판매순위 파일 → CSV → 로컬 DB
 * </pre>
 */
@Component
@ConditionalOnProperty("wayon.seed")
public class SeedRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

	private final RestAreaSeeder restArea;
	private final ConfigurableApplicationContext context;
	private final org.springframework.core.env.Environment env;
	private final String mode;

	public SeedRunner(RestAreaSeeder restArea, ConfigurableApplicationContext context,
			org.springframework.core.env.Environment env) {
		this.restArea = restArea;
		this.context = context;
		this.env = env;
		this.mode = env.getProperty("wayon.seed");
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {
		int code = 0;
		try {
			switch (mode) {
				case "rest-area-fetch" -> {
					restArea.fetch();
					restArea.load();
				}
				case "rest-area-load" -> restArea.load();
				case "top5-import" -> {
					String file = env.getProperty("wayon.seed.file");
					if (file == null) {
						throw new IllegalArgumentException("--wayon.seed.file=<판매순위 파일 경로>가 필요합니다");
					}
					restArea.importTop5(java.nio.file.Path.of(file));
					restArea.load();
				}
				default -> throw new IllegalArgumentException("모르는 wayon.seed 값: " + mode
						+ " (rest-area-fetch, rest-area-load, top5-import 중 하나)");
			}
			log.info("적재 끝: {}", mode);
		} catch (Exception e) {
			log.error("적재 실패: {}", mode, e);
			code = 1;
		}
		int exit = code;
		System.exit(SpringApplication.exit(context, () -> exit));
	}
}
