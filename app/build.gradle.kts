plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "za.co.impilodrilling.payroll"
    compileSdk = 35
    defaultConfig {
        applicationId = "za.co.impilodrilling.payroll"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}
