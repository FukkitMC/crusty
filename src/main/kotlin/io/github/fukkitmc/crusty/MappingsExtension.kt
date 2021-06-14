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

import net.fabricmc.lorenztiny.TinyMappingsReader
import net.fabricmc.lorenztiny.TinyMappingsWriter
import net.fabricmc.mapping.tree.TinyMappingFactory
import org.cadixdev.lorenz.io.MappingFormats
import org.cadixdev.lorenz.io.proguard.ProGuardFormat
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.dependencies.DefaultSelfResolvingDependency
import org.gradle.api.internal.file.FileCollectionInternal
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import java.net.URI
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

open class MappingsExtension(private val project: Project) {

    private val output = project.buildDir.parentFile.resolve(".gradle").resolve("crusty-1.17.jar")

    fun mappings(): Dependency {
        project.repositories.maven {
            it.name = "FabricMC (Intermediaries)"
            it.url = URI("https://maven.fabricmc.net/")

            it.mavenContent { contentDescriptor ->
                contentDescriptor.includeModule("net.fabricmc", "intermediary")
            }
        }

        if (!output.exists()) {
            populate()
        }

        val id = DefaultModuleComponentIdentifier(DefaultModuleIdentifier.newId("org.spigotmc", "mappings"), "crusty-1.17")

        return object : DefaultSelfResolvingDependency(id, project.files(output) as FileCollectionInternal) {
            override fun getGroup(): String = id.group
            override fun getName(): String = id.module
            override fun getVersion(): String = id.version
        }
    }

    private fun populate() {
        val intermediary2official = project.run {
            ZipFile(configurations.detachedConfiguration(dependencies.create("net.fabricmc:intermediary:1.17")).singleFile).use { zip ->
                zip.getInputStream(zip.getEntry("mappings/mappings.tiny")).bufferedReader().use { reader ->
                    TinyMappingsReader(TinyMappingFactory.loadWithDetection(reader), "intermediary", "official").read()
                }
            }
        }

        val official2bukkit = generateMappings(
            URL("https://hub.spigotmc.org/stash/projects/SPIGOT/repos/builddata/raw/mappings/bukkit-1.17-cl.csrg?at=refs%2Fheads%2Fmaster").openStream()
                .use {
                    MappingFormats.CSRG.createReader(it).read()
                },
            URL("https://hub.spigotmc.org/stash/projects/SPIGOT/repos/builddata/raw/mappings/bukkit-1.17-members.csrg?at=refs%2Fheads%2Fmaster").openStream()
                .use {
                    MappingFormats.CSRG.createReader(it).read()
                },
            URL("https://launcher.mojang.com/v1/objects/84d80036e14bc5c7894a4fad9dd9f367d3000334/server.txt").openStream()
                .use {
                    ProGuardFormat().createReader(it).read()
                },
        ).reverse()
        val intermediary2bukkit = intermediary2official.merge(official2bukkit)

        intermediary2bukkit.addFieldTypeProvider { field ->
            intermediary2official.getClassMapping(field.parent.fullObfuscatedName)
                .flatMap { it.getFieldMapping(field.obfuscatedName) }
                .flatMap { it.type }
        }

        // Patches
        // net/minecraft/class_1915.method_8260()Lnet/minecraft/class_1937; -> getTraderWorld
        intermediary2bukkit.getClassMapping("net/minecraft/class_1915")
            .flatMap { it.getMethodMapping("method_8260", "()Lnet/minecraft/class_1937;") }
            .ifPresent { it.deobfuscatedName = "getTraderWorld" }

        ZipOutputStream(output.outputStream()).use {
            it.putNextEntry(ZipEntry("mappings/mappings.tiny"))
            it.bufferedWriter().use { writer ->
                TinyMappingsWriter(writer, "intermediary", "named").write(intermediary2bukkit)
            }
        }
    }
}
