# Scala language support for Android

Tested on [a popular Android App](https://play.google.com/store/apps/details?id=com.soundcorset.client.android).

## Supported versions

* Scala 2.13.7 or above
* Scala 2.11.12
* Gradle 8.2.x or above
* Android Plugin 8.2.x or above

## Installation

### Apply plugin

`build.gradle.kts`
```kotlin
buildscript {
    dependencies {
        classpath("com.soundcorset:scala-android-plugin:24.0606.2139")
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
    implementation("org.scala-lang:scala-library:2.13.13")
}
```

## Example project

https://github.com/onsqcorp/hello-scala-android

## Build from the source and apply it to your project

 * Clone this repository
 * Run `publishToMavenLocal` gradle command
 * In the console, the artifact name `com.soundcorset:scala-android-plugin:yy.MMdd.HHmm` will be displayed (version changed for each time).
 * Set it in your project's `build.gradle.kts`. 
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
