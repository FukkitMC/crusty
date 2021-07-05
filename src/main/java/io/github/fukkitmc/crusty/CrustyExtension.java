package io.github.fukkitmc.crusty;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.text.MessageFormat;

import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import io.github.fukkitmc.crusty.util.DownloadUtil;
import io.github.fukkitmc.crusty.util.ExecuteUtil;
import io.github.fukkitmc.crusty.util.MapUtil;
import io.github.fukkitmc.crusty.util.ZipUtils;
import org.gradle.api.Project;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

public class CrustyExtension {
	private static final Logger ALTERNATIVE = Logging.getLogger(CrustyExtension.class);

	public static final Gson GSON = new Gson();
	private static final String JAVA_BUILD_DATA_COMMAND = "java -jar BuildData/";
	public final Project project;
	public final Path cache;
	public final boolean isOffline;

	public CrustyExtension(Project project) {
		this.project = project;
		if(project != null) {
			Gradle gradle = project.getGradle();
			this.isOffline = gradle.getStartParameter().isOffline();
			this.cache = gradle.getGradleUserHomeDir().toPath().resolve("caches").resolve("crusty");
		} else {
			this.isOffline = false;
			this.cache = Paths.get("test");
		}
	}

	public Path getLatestBuildData() {
		return this.getBuildData("https://hub.spigotmc.org/stash/rest/api/latest/projects/SPIGOT/repos/builddata/archive?format=zip");
	}

	public Path getBuildData(String url) {
		Hasher hasher = Hashing.sha256().newHasher();
		hasher.putString(url, StandardCharsets.UTF_8);
		String file = hasher.hash().toString();
		Path data = this.cache.resolve("buildata").resolve(file + ".zip");
		if(!Files.exists(data)) {
			this.getLogger().lifecycle("Downloading BuildData " + url);
			this.download(data, url, false);
		}
		return data;
	}

	public Logger getLogger() {
		if(this.project == null) {
			return ALTERNATIVE;
		}
		return this.project.getLogger();
	}

	public void download(Path to, String url, boolean compress) {
		try {
			Files.createDirectories(to.getParent());

			String etag = null;
			Path etagPath = to.getParent().resolve(to.getFileName() + ".etag");
			if(Files.exists(etagPath)) {
				try(BufferedReader reader = Files.newBufferedReader(etagPath)) {
					etag = reader.readLine();
				}
			}

			long lastModifyTime;
			if(Files.exists(to)) {
				lastModifyTime = Files.getLastModifiedTime(to).toMillis();
			} else {
				lastModifyTime = -1;
			}

			DownloadUtil.Result result = DownloadUtil.read(new URL(url), etag, lastModifyTime, this.getLogger(), this.isOffline, compress);
			if(result == null) {
				return;
			}

			try(InputStream input = result.stream) { // Try download to the output
				Files.copy(input, to, StandardCopyOption.REPLACE_EXISTING);
				result.clock.close();
			}

			//Set the modify time to match the server's (if we know it)
			if(result.lastModifyDate > 0) {
				Files.setLastModifiedTime(to, FileTime.fromMillis(result.lastModifyDate));
			}

			String output = result.etag;
			if(output != null) {
				try(BufferedWriter writer = Files.newBufferedWriter(etagPath)) {
					writer.write(output);
				}
			}
		} catch(IOException e) {
			throw new RuntimeException(e);
		}
	}

	public Path getBuildDataByCommit(String commit) {
		return this.getBuildData(String.format(
				"https://hub.spigotmc.org/stash/rest/api/latest/projects/SPIGOT/repos/builddata/archive?at=%s&format=zip",
				commit));
	}

	public Path getCrustySources(Path buildData) {
		Path cache = this.cache;
		try(FileSystem system = FileSystems.newFileSystem(buildData, null)) {
			BuildDataInfo info;
			try(BufferedReader reader = Files.newBufferedReader(system.getPath("info.json"))) {
				info = GSON.fromJson(reader, BuildDataInfo.class);
			}

			Hasher hasher = Hashing.sha256().newHasher();
			hasher.putString(buildData.toAbsolutePath().toString(), StandardCharsets.UTF_8);
			String file = hasher.hash().toString();

			Path buildDataCache = cache.resolve("craftbukkit").resolve(file);
			Files.createDirectories(buildDataCache);
			Path minecraftCache = cache.resolve("minecraft").resolve(info.minecraftVersion);

			Path vanillaJar = minecraftCache.resolve("server.jar");
			if(!Files.exists(vanillaJar)) {
				this.getLogger().lifecycle("Downloading Server Jar");
				this.download(vanillaJar, info.serverUrl, false);
			}

			Path mappings = system.getPath("mappings");
			Path classMappings = buildDataCache.resolve(info.classMappings);
			if(!Files.exists(classMappings)) {
				Files.copy(mappings.resolve(info.classMappings), classMappings);
			}
			Path memberMappings = buildDataCache.resolve(info.memberMappings);
			if(!Files.exists(memberMappings)) {
				Files.copy(mappings.resolve(info.memberMappings), memberMappings);
			}
			Path accessTransformers = buildDataCache.resolve(info.accessTransforms);
			if(!Files.exists(accessTransformers)) {
				Files.copy(mappings.resolve(info.accessTransforms), accessTransformers);
			}

			String excludeName = "bukkit-" + info.minecraftVersion + ".exclude";
			Path exclude = buildDataCache.resolve("bukkit.exclude");
			if(!Files.exists(exclude)) {
				Files.copy(mappings.resolve(excludeName), exclude);
			}

			Path finalMappings;
			if(info.mappingsUrl != null) {
				Path mojmap = minecraftCache.resolve("mojmap.txt");
				if(!Files.exists(mojmap)) {
					this.getLogger().lifecycle("Downloading Mojmap");
					this.download(mojmap, info.mappingsUrl, true);
				}

				finalMappings = buildDataCache.resolve("fields.csrg");
				if(!Files.exists(finalMappings)) {
					this.getLogger().lifecycle("Creating Field Mappings");
					MapUtil mapUtil = new MapUtil();
					mapUtil.loadBuk(classMappings);

					if(!Files.exists(finalMappings)) {
						mapUtil.makeFieldMaps(mojmap, finalMappings);
					}
				}
			} else if(info.packageMappings != null) {
				finalMappings = buildDataCache.resolve(info.packageMappings);
				if(!Files.exists(finalMappings)) {
					Files.copy(mappings.resolve(info.packageMappings), finalMappings);
				}
			} else {
				throw new RuntimeException("no mojmap or package mappings!");
			}

			if(info.classMapCommand == null) {
				info.classMapCommand = "java -jar BuildData/bin/SpecialSource-2.jar map -i {0} -m {1} -o {2}";
			}

			info.classMapCommand = info.classMapCommand.replace("BuildData/mappings/"+excludeName, exclude.toString());

			if(info.memberMapCommand == null) {
				info.memberMapCommand = "java -jar BuildData/bin/SpecialSource-2.jar map -i {0} -m {1} -o {2}";
			}

			if(info.finalMapCommand == null) {
				info.finalMapCommand = "java -jar BuildData/bin/SpecialSource.jar --kill-lvt -i {0} --access-transformer {1} -m {2} -o {3}";
			}

			Path classMapped = buildDataCache.resolve("class-mapped-server.jar");
			if(!Files.exists(classMapped)) {
				this.getLogger().lifecycle("Mapping Class Names");
				this.execute(system, buildDataCache, MessageFormat.format(info.classMapCommand, vanillaJar, classMappings, classMapped));
			}

			Path memberMapped = buildDataCache.resolve("member-mapped.jar");
			if(!Files.exists(memberMapped)) {
				this.getLogger().lifecycle("Mapping Members");
				this.execute(system, buildDataCache, MessageFormat.format(info.memberMapCommand, classMapped, memberMappings, memberMapped));
			}

			Path finalMapped = buildDataCache.resolve("final-mapped.jar");
			if(!Files.exists(finalMapped)) {
				this.getLogger().lifecycle("Mapping packages/fields");
				this.execute(system, buildDataCache, MessageFormat.format(info.finalMapCommand, memberMapped, accessTransformers, accessTransformers, finalMapped));
			}

			Path finalClasses = buildDataCache.resolve("final_classes");
			if(!Files.exists(finalClasses)) {
				Files.createDirectories(finalClasses);
				this.getLogger().lifecycle("Unzipping mapped jar");
				ZipUtils.unzip(finalMapped, finalClasses);
			}

			Path decompileDir = buildDataCache.resolve("final_sources");
			if(!Files.exists(decompileDir)) {
				Files.createDirectories(decompileDir);
				this.getLogger().lifecycle("Decompiling Sources");
				this.execute(system, buildDataCache, MessageFormat.format(info.decompileCommand, finalClasses, decompileDir));
			}

			return decompileDir;
		} catch(IOException e) {
			throw new RuntimeException(e);
		}
	}

	public void execute(FileSystem jarSystem, Path dir, String command) throws IOException {
		if(command.startsWith(JAVA_BUILD_DATA_COMMAND)) {
			this.getLogger().lifecycle("Using classloader hack for " + command);
			int len = JAVA_BUILD_DATA_COMMAND.length(), start = command.indexOf(' ', len);
			String jarPath = command.substring(len, start);

			Path destJar = dir.resolve(jarPath);
			if(!Files.exists(destJar)) {
				Files.createDirectories(destJar.getParent());
				Files.copy(jarSystem.getPath(jarPath), destJar);
			}

			String[] args = command.substring(start + 1).split(" ");
			ExecuteUtil.execute(destJar, args);
		} else {
			try {
				int returnCode = new ProcessBuilder().command(command.split(" ")).redirectOutput(ProcessBuilder.Redirect.INHERIT).start().waitFor();
			} catch(InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
	}

	public static void main(String[] args) {
		CrustyExtension extension = new CrustyExtension(null);
		Path latest = extension.getLatestBuildData();
		extension.getCrustySources(latest);
	}
}
