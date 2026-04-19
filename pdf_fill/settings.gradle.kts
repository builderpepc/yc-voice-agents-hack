import java.util.Properties

val localProps = Properties().also { props ->
    file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                username = localProps.getProperty("github_user")
                    ?: System.getenv("GITHUB_USERNAME") ?: ""
                password = localProps.getProperty("github_token")
                    ?: System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}

rootProject.name = "WearableAI"
include(":app", ":shared")
