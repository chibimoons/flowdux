plugins {
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.kotlinx.kover")
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)

    // Only sign when signing credentials are available (skipped during dry-run)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        url.set("https://github.com/chibimoons/flowdux")

        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }

        developers {
            developer {
                id.set("chibimoons")
                name.set("chibimoons")
                url.set("https://github.com/chibimoons")
            }
        }

        scm {
            url.set("https://github.com/chibimoons/flowdux")
            connection.set("scm:git:git://github.com/chibimoons/flowdux.git")
            developerConnection.set("scm:git:ssh://git@github.com/chibimoons/flowdux.git")
        }
    }
}
