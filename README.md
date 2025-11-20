# Scala language support for Android

Tested on [a popular Android App](https://play.google.com/store/apps/details?id=com.soundcorset.client.android).

## Supported versions

* Scala 2.13.7 or later, Scala 3.3.3 or later
* Gradle 8.13 or later
* [Android Gradle Plugin](https://developer.android.com/build/releases/gradle-plugin) 8.4.x or later

## Example project

https://github.com/onsqcorp/hello-scala-android

## Installation

In `build.gradle.kts`
```kotlin
plugins {
    id("com.soundcorset.scala-android") version "25.0417.2204"
    // ...
}
scala.scalaVersion = "3.7.3"
```

## Build from the source and apply it to your project

 * Clone this repository
 * Run `publishToMavenLocal` gradle command
 * In the console, the artifact information `groupId: com.soundcorset, version: yy.MMdd.HHmm` will be displayed (version changed for each time).
 * Set `mavenLocal()` in your project's `settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        mavenLocal() // needed to access local repository
        // ...
    }
}
```
 * Set correct version of the plugin in your project's `build.gradle.kts`:
```kotlin
plugins {
    id("com.soundcorset.scala-android") version "yy.MMdd.HHmm"
    // ...
}
scala.scalaVersion = ...
```
