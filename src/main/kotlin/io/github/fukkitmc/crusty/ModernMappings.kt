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

import org.cadixdev.bombe.type.signature.FieldSignature
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.model.ClassMapping
import org.cadixdev.lorenz.model.InnerClassMapping

/**
 * Generates Bukkit Named -> Official mappings
 *
 * @param classes The classes CSRG, represented Official -> Bukkit Named
 * @param members The members CSRG, represented Official -> Bukkit Named, using Bukkit types
 * @param mojang The Mojang client mappings
 */
internal fun generateMappings(
    classes: MappingSet,
    members: MappingSet,
    mojang: MappingSet,
): MappingSet {
    val classes = classes.reverse()
    val officialToNamed = mutableMapOf<String, ClassMapping<*, *>>().apply {
        classes.iterate {
            put(it.fullDeobfuscatedName, it)
        }
    }

    // Methods are weird; their types are named but their own defined names are official -> intermediary
    // Time to extract them
    members.iterate {
        val classMapping = classes.getOrCreateClassMapping(it.fullObfuscatedName)

        for (mapping in it.methodMappings) {
            classMapping
                .createMethodMapping(mapping.deobfuscatedName, mapping.descriptor)
                .deobfuscatedName = mapping.obfuscatedName
        }
    }

    // Just copy the field names
    val joined = mojang.merge(classes.reverse())

    mojang.iterate {
        val classMapping = officialToNamed[it.fullDeobfuscatedName] ?: return@iterate copy(it, classes, officialToNamed)

        for (fieldMapping in it.fieldMappings) {
            classMapping.createFieldMapping(fieldMapping.signature.run {
                FieldSignature(name, joined.deobfuscate(type.get()))
            }, fieldMapping.deobfuscatedName)
        }
    }

    return classes
}

private fun copy(mapping: ClassMapping<*, *>, classes: MappingSet, map: MutableMap<String, ClassMapping<*, *>>) {
    if (mapping is InnerClassMapping) {
        copy(mapping.parent, classes, map)
    }

    if (!classes.getClassMapping(mapping.fullObfuscatedName).isPresent && !map.containsKey(mapping.fullDeobfuscatedName)) {
        val created = classes.getOrCreateClassMapping(mapping.fullObfuscatedName)
        created.deobfuscatedName = mapping.deobfuscatedName
        map[mapping.fullDeobfuscatedName] = created
    }
    // TODO: I don't think we have to copy members, but keep this in mind
}

private fun MappingSet.iterate(consumer: (ClassMapping<*, *>) -> Unit) {
    fun inner(mapping: ClassMapping<*, *>) {
        consumer(mapping)

        for (child in mapping.innerClassMappings) {
            inner(child)
        }
    }

    for (mapping in topLevelClassMappings) {
        inner(mapping)
    }
}
