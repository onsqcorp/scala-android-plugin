# Scala language support for Android

Tested on [a popular Android App](https://play.google.com/store/apps/details?id=com.soundcorset.client.android).

## Supported versions

* Scala 2.11.12, Scala 2.13.7 or later and Scala 3
* Gradle 8.2.x or later
* [Android Gradle Plugin](https://developer.android.com/build/releases/gradle-plugin) 8.2.x or later

## Example project

https://github.com/onsqcorp/hello-scala-android

## Installation

### Apply plugin

`build.gradle.kts`
```kotlin
buildscript {
    dependencies {
        classpath("com.soundcorset:scala-android-plugin:24.1019.1546")
        // ...
    }
}

apply(plugin = "com.soundcorset.scala-android")
```

### Add scala-library dependency

The plugin decides scala language version using scala-library's version.

`build.gradle.kts`
```kotlin
dependencies {
    implementation("org.scala-lang:scala-library:2.13.16")
}
```

## Build from the source and apply it to your project

 * Clone this repository
 * Run `publishToMavenLocal` gradle command
 * In the console, the artifact name `com.soundcorset:scala-android-plugin:yy.MMdd.HHmm` will be displayed (version changed for each time).
 * Set it in your project's `build.gradle.kts`:
```kotlin
buildscript {
    repositories {
        mavenLocal() // needed to access local repository
        // ...
    }
    dependencies {
        classpath("com.soundcorset:scala-android-plugin:yy.MMdd.HHmm")
        // ...
    }
}
```
