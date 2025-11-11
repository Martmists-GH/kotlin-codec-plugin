# Kotlin Codec Plugin

A kotlin compiler plugin to create codecs for Mojang's Codec system.

## Usage

> Important: To make the CODEC field resolve in IntelliJ, make sure to open the registry and disable `kotlin.k2.only.bundled.compiler.plugins.enabled`

Add the `codec-annotations` and `codec-plugin` folders to your project, then add to your build files:

```kotlin
// build.gradle.kts
plugins {
    ...
    id("com.martmists.serialization")
}

dependencies {
    ...
    implementation(project(":codec-annotations"))
}

// settings.gradle.kts
include(":codec-annotations")
includeBuild("codec-plugin")
```

Then in your code:

```kotlin
import com.martmists.serialization.Record

@Record
data class MyType(
    val x: Int,
    val y: String,
)

// elsewhere
val codec: Codec<MyType> = MyType.CODEC
```
