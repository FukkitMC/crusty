/*
 * Copyright 2020 ramidzkh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `java-gradle-plugin`
    `maven-publish`
    kotlin("jvm") version "1.5.10"
}

group = "xyz.fukkit"
version = "2.2.2"

repositories {
    mavenCentral()

    maven {
        name = "FabricMC"
        url = uri("https://maven.fabricmc.net/")
    }
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.cadixdev", "lorenz", "0.5.7")
    implementation("org.cadixdev", "lorenz-io-proguard", "0.5.7")
    implementation("net.fabricmc", "lorenz-tiny", "3.0.0")
    implementation("com.google.guava:guava:30.1.1-jre")
    implementation("com.google.code.gson:gson:2.8.7")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

gradlePlugin {
    plugins {
        create("plugin") {
            id = "xyz.fukkit.crusty"
            implementationClass = "io.github.fukkitmc.crusty.MappingsPlugin"
        }
    }
}

publishing {
    repositories {
        maven {
            name = "Fukkit"
            url = uri("gcs://devan-maven")
        }
    }
}
