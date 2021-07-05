/*
 * Copyright (c) 2014, SpigotMC. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * The name of the author may not be used to endorse or promote products derived
 * from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package io.github.fukkitmc.crusty.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;

public class MapUtil {

	private static final Pattern MEMBER_PATTERN = Pattern.compile("(?:\\d+:\\d+:)?(.*?) (.*?) \\-> (.*)");
	private final BiMap<String, String> obf2Buk = HashBiMap.create();
	//
	private List<String> header = new ArrayList<>();

	public void loadBuk(Path bukClasses) throws IOException {
		for(String line : Files.readAllLines(bukClasses)) {
			if(line.startsWith("#")) {
				header.add(line);
				continue;
			}

			String[] split = line.split(" ");
			if(split.length == 2) {
				obf2Buk.put(split[0], split[1]);
			}
		}
	}

	public void makeFieldMaps(Path mojIn, Path fields) throws IOException {
		List<String> outFields = new ArrayList<>(header);

		String currentClass = null;
		outer:
		for(String line : Files.readAllLines(mojIn)) {
			if(line.startsWith("#")) {
				continue;
			}
			line = line.trim();

			if(line.endsWith(":")) {
				currentClass = null;

				String[] parts = line.split(" -> ");
				//String orig = parts[0].replace('.', '/');
				String obf = parts[1].substring(0, parts[1].length() - 1).replace('.', '/');

				String buk = deobfClass(obf, obf2Buk);
				if(buk == null) {
					continue;
				}

				currentClass = buk;
			} else if(currentClass != null) {
				Matcher matcher = MEMBER_PATTERN.matcher(line);
				matcher.find();

				String obf = matcher.group(3);
				String nameDesc = matcher.group(2);
				if(!nameDesc.contains("(")) {
					if(nameDesc.contains("$")) {
						continue;
					}
					if(obf.equals("if") || obf.equals("do")) {
						obf += "_";
					}

					outFields.add(currentClass + " " + obf + " " + nameDesc);
				}
			}
		}

		Collections.sort(outFields);
		Files.write(fields, outFields);
	}

	public static String deobfClass(String obf, Map<String, String> classMaps) {
		String buk = classMaps.get(obf);
		if(buk == null) {
			StringBuilder inner = new StringBuilder();

			while(buk == null) {
				int idx = obf.lastIndexOf('$');
				if(idx == -1) {
					return null;
				}
				inner.insert(0, obf.substring(idx));
				obf = obf.substring(0, idx);

				buk = classMaps.get(obf);
			}

			buk += inner;
		}
		return buk;
	}
}