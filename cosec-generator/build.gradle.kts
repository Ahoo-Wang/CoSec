description = "Generate policy statement based on Open API"

dependencies {
    api(project(":cosec-core"))
    api("io.swagger.core.v3:swagger-core-jakarta")
    testImplementation("org.springframework:spring-web")
    testImplementation("org.springframework:spring-expression")
}
