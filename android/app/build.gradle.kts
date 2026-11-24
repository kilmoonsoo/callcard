plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions에서 빌드할 때 버전 번호와 서명 키를 환경변수로 받습니다.
val ciVersionCode = System.getenv("CALLCARD_VERSION_CODE")?.toIntOrNull()
val keystorePath = System.getenv("CALLCARD_KEYSTORE")
val updateRepo = System.getenv("GITHUB_REPOSITORY") ?: (project.findProperty("callcard.repo") as String? ?: "")

android {
    namespace = "app.callcard"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.callcard"
        minSdk = 26
        targetSdk = 34
        versionCode = ciVersionCode ?: 2
        versionName = "1.${ciVersionCode ?: 2}"
        // 새 버전을 확인할 GitHub 저장소 (예: owner/callcard)
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("CALLCARD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CALLCARD_KEY_ALIAS")
                keyPassword = System.getenv("CALLCARD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 배포용은 고정 키로 서명해야 업데이트 설치가 됩니다. 키가 없으면(로컬 빌드) 디버그 키를 씁니다.
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
