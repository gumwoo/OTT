plugins {
	java
	checkstyle
	jacoco
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
	id("io.spring.javaformat") version "0.0.48"
}

group = "dev.gumwoo"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-session-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation(platform("software.amazon.awssdk:bom:2.57.3"))
	// 쿠키 서명 유틸리티만 쓰므로 HTTP 클라이언트는 빼낸다
	implementation("software.amazon.awssdk:cloudfront") {
		exclude(group = "software.amazon.awssdk", module = "apache-client")
		exclude(group = "software.amazon.awssdk", module = "apache5-client")
		exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
	}
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-session-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// SP-10: 테스트 DB는 Testcontainers PostgreSQL만
configurations.all {
	resolutionStrategy.eachDependency {
		if (requested.group == "com.h2database") {
			throw GradleException("H2 is not allowed (CONVENTIONS SP-10): ${requested}")
		}
	}
}

checkstyle {
	toolVersion = "12.3.1" // 13부터 JDK 21 필요
	configFile = file("config/checkstyle/checkstyle.xml")
	maxWarnings = 0
}

tasks.withType<Test> {
	useJUnitPlatform()
	finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
	reports {
		xml.required = true
		html.required = true
	}
}
