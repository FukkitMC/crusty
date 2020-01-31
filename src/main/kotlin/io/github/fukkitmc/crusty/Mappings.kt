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

import org.objectweb.asm.Type
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class Member(val owner: String, val name: String, val desc: String)

internal fun createMappings(intermediary: File, c: File, m: File, output: File) {
    val classMap = mutableMapOf<String, String>()
    val fieldMap = mutableMapOf<Member, String>()
    val methodMap = mutableMapOf<Member, String>()
    val fieldDescriptorMap = mutableMapOf<Pair<String, String>, String>()

    intermediary.forEachLine {
        val parts = it.split("\t")

        when (parts[0]) {
            "CLASS" -> classMap[parts[1]] = parts[2]
            "METHOD" -> methodMap[Member(parts[1], parts[3], parts[2])] = parts[4]
            "FIELD" -> {
                fieldMap[Member(parts[1], parts[3], parts[2])] = parts[4]
                fieldDescriptorMap[parts[1] to parts[3]] = parts[2]
            }
            else -> println("Skipped line $it")
        }
    }

    val crustyClassMap = mutableMapOf<String, String>()
    val crustyFieldMap = mutableMapOf<Member, String>()
    val crustyMethodMap = mutableMapOf<Member, String>()
    val crustyUnClassMap = mutableMapOf<String, String>()

    c.forEachLine {
        val parts = it.split(" ")

        if (parts.size == 2) {
            crustyClassMap[parts[0]] = parts[1]
            crustyUnClassMap[parts[1]] = parts[0]
        }
    }

    fun Map<String, String>.g(name: String): String? = this[name] ?: run {
        val i = name.lastIndexOf('$').let { if (it > 0) it else name.length }
        val a = name.substring(0, i)
        val b = name.substring(i)

        if (b.isEmpty()) {
            get(name)
        } else {
            g(a)?.let { "$it$b" }
        }
    }

    fun map(type: Type): Type {
        return if (type.sort == Type.OBJECT) {
            Type.getObjectType(crustyUnClassMap.g(type.internalName) ?: type.internalName)
        } else {
            type
        }
    }

    fun mapDesc(desc: String): String {
        return Type.getMethodDescriptor(map(Type.getReturnType(desc)), *Type.getArgumentTypes(desc).map { map(it) }.toTypedArray())
    }

    m.forEachLine {
        val parts = it.split(" ")

        when (parts.size) {
            3 -> {
                val owner = crustyUnClassMap.g(parts[0]) ?: parts[0]
                crustyFieldMap[Member(owner, parts[1], fieldDescriptorMap[owner to parts[1]]!!)] = parts[2]
            }
            4 -> {
                val owner = crustyUnClassMap.g(parts[0]) ?: parts[0]
                crustyMethodMap[Member(owner, parts[1], mapDesc(parts[2]))] = parts[3]
            }
        }
    }

    ZipOutputStream(output.outputStream()).use { zip ->
        zip.putNextEntry(ZipEntry("mappings/mappings.tiny"))

        val writer = zip.bufferedWriter()
        writer.write("v1\tofficial\tintermediary\tnamed\n")

        classMap.forEach { (official, intermediary) ->
            val crusty = crustyClassMap.g(official)?.let { crusty ->
                if (crusty.contains('/')) {
                    crusty
                } else {
                    "net/minecraft/server/$crusty"
                }
            } ?: intermediary
            writer.write("CLASS\t$official\t$intermediary\t$crusty\n")
        }

        methodMap.forEach { (member, intermediary) ->
            val crusty = crustyMethodMap[member] ?: member.name
            writer.write("METHOD\t${member.owner}\t${member.desc}\t${member.name}\t$intermediary\t$crusty\n")
        }

        fieldMap.forEach { (member, intermediary) ->
            val crusty = crustyFieldMap[member] ?: member.name
            writer.write("FIELD\t${member.owner}\t${member.desc}\t${member.name}\t$intermediary\t$crusty\n")
        }

        writer.flush()
        zip.closeEntry()
    }
}
