# `crusty`
FukkitMC's simple Gradle plugin to use SpigotMC's mappings.

## Usage
Requires `git` to be on the path.
```groovy
plugins {
    id 'io.github.fukkitmc.crusty' version '1.0.0'
}

dependencies {
    mappings(fukkit.mappings('1.15.1'))
}
```
```kotlin
plugins {
    id("io.github.fukkitmc.crusty") version "1.0.0"
}

dependencies {
    mappings(fukkit.mappings("1.15.1"))
}
```

## License
Apache 2.0
