dependencies {
    api(project(":cosec-core"))
    api("me.ahoo.cosid:cosid-core")
    api("org.springframework.data:spring-data-redis")
    api("me.zhyd.oauth:JustAuth")
    testImplementation("me.ahoo.cosid:cosid-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("io.lettuce:lettuce-core")
}
