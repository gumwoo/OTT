// Plugin Portal을 거치면 CI의 이미지 빌드에서 플러그인 marker 조회가 간헐적으로 실패했다. marker는 모두 Central에도 있다 (TS-0010)
pluginManagement {
	repositories {
		mavenCentral()
		gradlePluginPortal()
	}
}

rootProject.name = "api"
