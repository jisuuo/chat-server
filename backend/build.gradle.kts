plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "jissuo"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-aspectj")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-websocket")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.flywaydb:flyway-mysql")
	runtimeOnly("com.mysql:mysql-connector-j")
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-mysql")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
	systemProperty("LOG_DIR", layout.buildDirectory.dir("test-logs").get().asFile.absolutePath)
	// 여러 캐시된 테스트 컨텍스트의 유휴 풀이 MySQL 연결 한도를 채우지 않게 한다.
	systemProperty("spring.datasource.hikari.minimum-idle", "0")
}

tasks.test {
	useJUnitPlatform {
		excludeTags("experiment")
	}
}

tasks.register<Test>("experimentTest") {
	description = "Runs experiment-tagged tests only."
	group = "verification"
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform {
		includeTags("experiment")
	}
}
