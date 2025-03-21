import java.util.Date
import java.text.SimpleDateFormat

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
val ver: String = SimpleDateFormat("yy.MMdd.HHmm").format(Date())
group = grp
version = ver

println("groupId: $grp, version: $ver")

dependencies {
    compileOnly("com.android.tools.build:gradle:8.9.0")
    compileOnly("org.jetbrains.kotlin.android:org.jetbrains.kotlin.android.gradle.plugin:2.1.10")
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
                url = "https://github.com/onsqcorp/scala-android-plugin"
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
