plugins {
    id("com.android.application") version "8.9.2" apply false
    id("com.android.library") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
}

// The upstream SoftEther Android library reads these extra properties from `project.ext`.
allprojects {
    extensions.extraProperties.set("compileSdkVersion", 36)
    extensions.extraProperties.set("minSdkVersion", 26)
    extensions.extraProperties.set("targetSdkVersion", 35)
}
