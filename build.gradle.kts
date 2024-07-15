import java.util.Date
import java.text.SimpleDateFormat

val androidVersion = "8.3.2"

plugins {
    `java-gradle-plugin`
    groovy
    `maven-publish`
    signing
}

repositories {
    google()
    mavenCentral()
}

tasks.compileJava {
    options.release = 17
}

val grp = "com.soundcorset"
val ver = SimpleDateFormat("yy.MMdd.HHmm").format(Date())
group = grp
version = ver

println("groupId: $grp, version: $ver")

dependencies {
    implementation("com.android.tools.build:gradle:$androidVersion")
    implementation("org.jetbrains.kotlin.android:org.jetbrains.kotlin.android.gradle.plugin:2.0.0")
}

gradlePlugin {
    plugins {
        create("scalaAndroidPlugin") {
            id = "com.soundcorset.scala-android"
            implementationClass = "com.soundcorset.scala.android.plugin.ScalaAndroidPlugin"
        }
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = "scala-android-plugin"
            groupId = grp
            version = ver
            from(components["java"])
            pom {
                name = "Scala android plugin"
                description = "Scala android plugin"
                url = "https://github.com/onsquare/scala-android-plugin"
                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "http://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
                developers {
                    developer {
                        id = "onsqcorp"
                        name = "Onsquare Developer"
                        email = "onsqcorp@gmail.com"
                    }
                }
                scm {
                    connection = "https://github.com/onsqcorp/scala-android-plugin"
                    developerConnection = "https://github.com/onsqcorp/scala-android-plugin"
                    url = "https://github.com/onsqcorp/scala-android-plugin"
                }
            }
        }
    }
}
