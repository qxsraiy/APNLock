plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.sraiy.apnlock"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.sraiy.apnlock"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    implementation("io.github.iamr0s:Dhizuku-API:2.6.0")}
