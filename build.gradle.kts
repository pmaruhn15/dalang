buildscript {
    repositories {
        maven { url = uri("https://dl.google.com/dl/android/maven2") }
        maven { url = uri("https://repo1.maven.org/maven2") }
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.2.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
    }
}

allprojects {
    repositories {
        maven { url = uri("https://dl.google.com/dl/android/maven2") }
        maven { url = uri("https://repo1.maven.org/maven2") }
    }
}
