plugins {
    java
    id("org.springframework.boot") version "4.0.1"
}

group = "com.mirboard"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // Spring Boot 4.0 BOM — Gradle 9.x 에서는 io.spring.dependency-management plugin
    // 대신 platform() 으로 BOM 을 직접 import 하는 게 호환성이 더 안전.
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.1"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.1"))

    // Web / WebSocket
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Spring Boot 4.0 에서 Jackson 의 ObjectMapper autoconfigure 도 별도 starter 로 분리됨.
    implementation("org.springframework.boot:spring-boot-starter-jackson")

    // Security + JWT
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // Persistence
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // Spring Boot 4.0 에서 Flyway autoconfigure 는 별도 starter 로 분리됨.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-mysql")
    runtimeOnly("com.mysql:mysql-connector-j")

    // Cache / 실시간 상태
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4.0 에서 WebMVC test 슬라이스 (AutoConfigureMockMvc 등) 가
    // starter-test 에서 빠지고 별도 starter (spring-boot-starter-webmvc-test) 로 분리됨.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:mysql:1.20.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-serial,-processing"))
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}
