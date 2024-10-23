import org.gradle.api.JavaVersion

object Config {

    const val compileSdk = 34
    const val minSdk = 24
    const val targetSdk = 34
    const val versionCode = 229
    const val versionName = "13.3.1"

    const val androidTestInstrumentation = "androidx.test.runner.AndroidJUnitRunner"

    val javaVersion = JavaVersion.VERSION_21
}