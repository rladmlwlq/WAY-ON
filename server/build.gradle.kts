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
	// Spring Initializr 목록엔 아직 없지만 4.1.x가 Spring Boot 4.1 지원 버전입니다
	implementation("org.mybatis.spring.boot:mybatis-spring-boot-starter:4.1.0")
	runtimeOnly("com.mysql:mysql-connector-j")

	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.mybatis.spring.boot:mybatis-spring-boot-starter-test:4.1.0")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
