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

    // Persistence — Phase 7-1 부터 PostgreSQL (D-39). Fly.io 배포 + 매니지드 Postgres 호환.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // Spring Boot 4.0 에서 Flyway autoconfigure 는 별도 starter 로 분리됨.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Cache / 실시간 상태
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // 운영 (Phase 6A-3) — Actuator + Prometheus 메트릭 노출
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4.0 에서 WebMVC test 슬라이스 (AutoConfigureMockMvc 등) 가
    // starter-test 에서 빠지고 별도 starter (spring-boot-starter-webmvc-test) 로 분리됨.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")
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

// Phase 7-4 (D-39) — Vite 클라이언트 번들을 Spring Boot jar 의 정적 리소스로 동봉.
//
// 사용 (옵션):
//   ./gradlew :server:bootJar -PbundleClient        # 클라 빌드 + 정적 동봉
//   ./gradlew :server:bootRun -PbundleClient        # 동봉 후 로컬 기동
//
// 미지정 시 처리되지 않으므로 기존 dev 흐름 (vite dev server) 은 영향 없음.
// Dockerfile 은 이 task 와 무관하게 자체 client-build 스테이지를 가짐.
val bundleClient = providers.gradleProperty("bundleClient").isPresent

tasks.register<Exec>("clientInstall") {
    description = "Run npm ci in client/"
    group = "build"
    workingDir = rootProject.file("client")
    commandLine("npm", "ci", "--no-audit", "--no-fund")
    inputs.file(rootProject.file("client/package.json"))
    inputs.file(rootProject.file("client/package-lock.json"))
    outputs.dir(rootProject.file("client/node_modules"))
}

tasks.register<Exec>("clientBuild") {
    description = "Build the Vite client bundle → client/dist"
    group = "build"
    dependsOn("clientInstall")
    workingDir = rootProject.file("client")
    commandLine("npm", "run", "build")
    inputs.dir(rootProject.file("client/src"))
    // Phase 8F (D-45) — 정적 자산 (cards/characters/board) 추가 시 gradle 이
    // up-to-date 캐시를 무효화하도록 public 디렉토리도 inputs 에 포함.
    inputs.dir(rootProject.file("client/public"))
    inputs.file(rootProject.file("client/index.html"))
    inputs.file(rootProject.file("client/vite.config.ts"))
    inputs.file(rootProject.file("client/tsconfig.json"))
    outputs.dir(rootProject.file("client/dist"))
}

if (bundleClient) {
    tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
        dependsOn("clientBuild")
        from(rootProject.file("client/dist")) {
            into("static")
        }
    }
}
