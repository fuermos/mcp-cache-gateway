plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.spring") version "1.9.24"
    id("org.springframework.boot") version "3.3.0"
    id("io.spring.dependency-management") version "1.1.4"
    kotlin("plugin.serialization") version "1.9.24"
}

group = "com.fuermos.mcp"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Database
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")
    implementation("com.zaxxer:HikariCP")

    // JSON-RPC + HTTP utilities
    implementation("com.fasterxml.uuid:java-uuid-generator:5.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.8.1")

    // Process management (借鉴 tubi-mcp wigolo-bridge 模式)
    implementation("org.zeroturnaround:zt-exec:1.12")

    // YAML config (tools.yaml)
    implementation("org.yaml:snakeyaml")

    // Logging
    implementation("ch.qos.logback:logback-classic")

    // Test (Day 1-4: skipped — network 170 B/s blocks Maven Central. Day 6 re-enabled
// with offline-cached versions: junit-jupiter 5.8.2 (vs 5.10.2 in spring-boot 3.3)
// + mockk 1.13.13. spring-boot-starter-test NOT cached — use plain junit + mockk.
testImplementation("org.junit.jupiter:junit-jupiter:5.8.2")
testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.2")
testImplementation("org.junit.jupiter:junit-jupiter-params:5.8.2")
testImplementation("org.junit.platform:junit-platform-commons:1.8.2")
testImplementation("io.mockk:mockk:1.13.13")
testImplementation("net.bytebuddy:byte-buddy:1.14.17")
testImplementation("net.bytebuddy:byte-buddy-agent:1.14.17")
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    // 覆盖率 >80% 阈值（pitest 或 jacoco）
    // 注：Phase 1 启用 jacoco
}

// Day 1-4: skip test compilation (junit-jupiter / kotlin-test-junit5 not in offline cache)
// Day 6: re-enable with cached versions
// tasks.named("compileTestKotlin") { enabled = false }
// tasks.named("compileTestJava") { enabled = false }
// tasks.named("test") { enabled = false }

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-opt-in=kotlin.RequiresOptIn"
        )
    }
}

springBoot {
    mainClass.set("com.fuermos.mcp.cache.gateway.ApplicationKt")
}
// Day 6: override spring-managed junit versions to match offline cache
configurations.all {
    resolutionStrategy {
        force(
            "org.junit.jupiter:junit-jupiter:5.8.2",
            "org.junit.jupiter:junit-jupiter-api:5.8.2",
            "org.junit.jupiter:junit-jupiter-engine:5.8.2",
            "org.junit.jupiter:junit-jupiter-params:5.8.2",
            "org.junit.platform:junit-platform-commons:1.8.2",
            "org.junit.platform:junit-platform-engine:1.8.2",
            "org.hamcrest:hamcrest-core:1.3"
        )
        eachDependency {
            // spring-boot 3.3 transitively pulls junit 5.10.2 → not in cache.
            // Force downgrade via substitution (5.8.2 for jupiter, 1.8.2 for platform).
            if (requested.group == "org.junit.jupiter" && requested.name.startsWith("junit-jupiter")) {
                useVersion("5.8.2")
            } else if (requested.group == "org.junit.platform" && requested.name.startsWith("junit-platform")) {
                useVersion("1.8.2")
            } else if (requested.group == "org.hamcrest") {
                useVersion("1.3")
            }
        }
    }
    // apiguardian-api not in offline cache — exclude from test runtime
    exclude(group = "org.apiguardian")
    // hamcrest 2.2 not in cache — force down to 1.3 via force above
}
