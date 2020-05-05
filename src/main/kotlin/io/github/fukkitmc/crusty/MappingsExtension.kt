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

package io.github.fukkitmc.crusty

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.dependencies.DefaultSelfResolvingDependency
import org.gradle.api.internal.file.FileCollectionInternal
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import java.io.InputStreamReader
import java.net.URI
import java.net.URL
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption

open class MappingsExtension(private val project: Project) {

    private val gson = Gson()
    private val buildData = project.buildDir.parentFile.resolve(".gradle").resolve("BuildData")

    fun mappings(version: String): Dependency {
        project.repositories.maven {
            it.name = "FabricMC (Intermediaries)"
            it.url = URI("https://maven.fabricmc.net/")

            it.mavenContent { contentDescriptor ->
                contentDescriptor.includeModule("net.fabricmc", "intermediary")
            }
        }

        return mappings(version, "net.fabricmc:intermediary:$version")
    }

    fun mappings(version: String, intermediaryNotation: String): Dependency {
        val output = project.buildDir.parentFile.resolve(".gradle").resolve("crusty-$version.jar")

        if (!output.exists()) {
            val checkout = URL("https://hub.spigotmc.org/versions/$version.json").openStream().use {
                gson.fromJson(InputStreamReader(it), JsonObject::class.java)
            }.getAsJsonObject("refs")["BuildData"].asString

            if (!buildData.resolve(".git").exists()) {
                buildData.deleteRecursively()

                project.exec { exec ->
                    exec.workingDir = buildData.parentFile
                    exec.commandLine("git", "clone", "https://hub.spigotmc.org/stash/scm/spigot/builddata.git", buildData.absolutePath)
                }

                project.exec { exec ->
                    exec.workingDir = buildData
                    exec.commandLine("git", "reset", "--hard", checkout)
                }
            } else {
                project.exec { exec ->
                    exec.workingDir = buildData
                    exec.commandLine("git", "reset", "--hard", checkout)
                }

                project.exec { exec ->
                    exec.workingDir = buildData
                    exec.commandLine("git", "pull")
                }
            }

            MappingsExtension::class.java.classLoader.getResource("patches/$version.patch")?.openStream()?.use {
                project.exec { exec ->
                    exec.workingDir = buildData
                    exec.isIgnoreExitValue = true
                    exec.commandLine("git", "am", "--abort")
                }

                project.exec { exec ->
                    exec.workingDir = buildData
                    exec.standardInput = it
                    exec.commandLine("git", "am")
                }
            }

            val data = buildData.resolve("info.json").bufferedReader().use { gson.fromJson(it, JsonObject::class.java) }

            val intermediaryJar = project.configurations.detachedConfiguration(project.dependencies.create(intermediaryNotation)).singleFile
            val (classMap, methodMap) = buildData.resolve("mappings").let { it.resolve(data["classMappings"].asString) to it.resolve(data["memberMappings"].asString) }

            val intermediary = Files.createTempFile("intermediary-$version-${intermediaryNotation.hashCode()}", ".tiny")

            FileSystems.newFileSystem(URI("jar:" + intermediaryJar.toURI()), mapOf<String, Any>()).use {
                Files.copy(it.getPath("mappings", "mappings.tiny"), intermediary, StandardCopyOption.REPLACE_EXISTING)
            }

            createMappings(intermediary.toFile(), classMap, methodMap, output, true)
        }

        val id = DefaultModuleComponentIdentifier(DefaultModuleIdentifier.newId("org.spigotmc", "mappings"), "crusty-$version-${intermediaryNotation.hashCode()}")

        return object : DefaultSelfResolvingDependency(id, project.files(output) as FileCollectionInternal) {
            override fun getGroup(): String = id.group
            override fun getName(): String = id.module
            override fun getVersion(): String = id.version
        }
    }
}
