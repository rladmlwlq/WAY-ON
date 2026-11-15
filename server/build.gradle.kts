plugins {
	java
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.wayon"
version = "0.1.0"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-websocket")
	// Spring Initializr 목록엔 아직 없지만 4.1.x가 Spring Boot 4.1 지원 버전입니다
	implementation("org.mybatis.spring.boot:mybatis-spring-boot-starter:4.1.0")
	// 오피넷 좌표계(KATEC) ↔ WGS84 (api.md 4)
	implementation("org.locationtech.proj4j:proj4j:1.4.1")
	runtimeOnly("com.mysql:mysql-connector-j")

	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.mybatis.spring.boot:mybatis-spring-boot-starter-test:4.1.0")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// -Pweb: 폰 웹(../web)을 빌드해 서버 정적 파일로 같이 내보낸다. 웹을 따로 배포하지 않고 서버 주소 하나로 쓸 때 (README)
val webDir = file("../web")
val buildWeb by tasks.registering(Exec::class) {
	workingDir = webDir
	val npm = if (System.getProperty("os.name").startsWith("Windows")) listOf("cmd", "/c", "npm") else listOf("npm")
	commandLine(npm + listOf("run", "build"))
	inputs.dir(webDir.resolve("src"))
	inputs.dir(webDir.resolve("public"))
	inputs.files(webDir.resolve("index.html"), webDir.resolve("package.json"), webDir.resolve(".env.local")).optional()
	outputs.dir(webDir.resolve("dist"))
}
if (project.hasProperty("web")) {
	tasks.processResources {
		dependsOn(buildWeb)
		from(webDir.resolve("dist")) { into("static") }
	}
}