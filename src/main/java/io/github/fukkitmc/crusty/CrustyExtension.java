package io.github.fukkitmc.crusty;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
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
	public static final Gson GSON = new Gson();
	private static final Logger ALTERNATIVE = Logging.getLogger(CrustyExtension.class);
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

	public static void main(String[] args) {
		CrustyExtension extension = new CrustyExtension(null);
		Path latest = extension.getLatestBuildData();
		extension.getCrustySources(latest);
	}

	public static void delete(Path path) throws IOException {
		if(Files.isDirectory(path)) {
			Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.delete(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
					Files.delete(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		} else {
			Files.deleteIfExists(path);
		}
	}

	public Path getLatestBuildData() {
		return this.getBuildData("https://hub.spigotmc.org/stash/rest/api/latest/projects/SPIGOT/repos/builddata/archive?format=zip");
	}

	public Path getBuildDataByCommit(String commit) {
		return this.getBuildData(String.format(
				"https://hub.spigotmc.org/stash/rest/api/latest/projects/SPIGOT/repos/builddata/archive?at=%s&format=zip",
				commit));
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

	public Path getCrustySources(Path buildData) {
		return this.getCrusty(buildData, true);
	}

	public Path getCrustyJar(Path buildData) {
		return this.getCrusty(buildData, false);
	}

	public Path getCrusty(Path buildData, boolean sources) {
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
			if(missing(vanillaJar)) {
				this.getLogger().lifecycle("Downloading Server Jar");
				this.download(vanillaJar, info.serverUrl, false);
				deleteMarker(vanillaJar);
			}

			Path mappings = system.getPath("mappings");
			Path classMappings = buildDataCache.resolve(info.classMappings);
			if(missing(classMappings)) {
				Files.copy(mappings.resolve(info.classMappings), classMappings);
				deleteMarker(classMappings);
			}
			Path memberMappings = buildDataCache.resolve(info.memberMappings);
			if(missing(memberMappings)) {
				Files.copy(mappings.resolve(info.memberMappings), memberMappings);
				deleteMarker(memberMappings);
			}
			Path accessTransformers = buildDataCache.resolve(info.accessTransforms);
			if(missing(accessTransformers)) {
				Files.copy(mappings.resolve(info.accessTransforms), accessTransformers);
				deleteMarker(accessTransformers);
			}

			String excludeName = "bukkit-" + info.minecraftVersion + ".exclude";
			Path exclude = buildDataCache.resolve("bukkit.exclude");
			if(missing(exclude)) {
				Files.copy(mappings.resolve(excludeName), exclude);
				deleteMarker(exclude);
			}

			Path finalMappings;
			if(info.mappingsUrl != null) {
				Path mojmap = minecraftCache.resolve("mojmap.txt");
				if(missing(mojmap)) {
					this.getLogger().lifecycle("Downloading Mojmap");
					this.download(mojmap, info.mappingsUrl, true);
					deleteMarker(mojmap);
				}

				finalMappings = buildDataCache.resolve("fields.csrg");
				if(missing(finalMappings)) {
					this.getLogger().lifecycle("Creating Field Mappings");
					MapUtil mapUtil = new MapUtil();
					mapUtil.loadBuk(classMappings);
					mapUtil.makeFieldMaps(mojmap, finalMappings);
					deleteMarker(finalMappings);
				}
			} else if(info.packageMappings != null) {
				finalMappings = buildDataCache.resolve(info.packageMappings);
				if(missing(finalMappings)) {
					Files.copy(mappings.resolve(info.packageMappings), finalMappings);
					deleteMarker(finalMappings);
				}
			} else {
				throw new RuntimeException("no mojmap or package mappings!");
			}

			if(info.classMapCommand == null) {
				info.classMapCommand = "java -jar BuildData/bin/SpecialSource-2.jar map -i {0} -m {1} -o {2}";
			}

			info.classMapCommand = info.classMapCommand.replace("BuildData/mappings/" + excludeName, exclude.toString());

			if(info.memberMapCommand == null) {
				info.memberMapCommand = "java -jar BuildData/bin/SpecialSource-2.jar map -i {0} -m {1} -o {2}";
			}

			if(info.finalMapCommand == null) {
				info.finalMapCommand = "java -jar BuildData/bin/SpecialSource.jar --kill-lvt -i {0} --access-transformer {1} -m {2} -o {3}";
			}

			Path classMapped = buildDataCache.resolve("class-mapped-server.jar");
			if(missing(classMapped)) {
				this.getLogger().lifecycle("Mapping Class Names");
				this.execute(system, buildDataCache, MessageFormat.format(info.classMapCommand, vanillaJar, classMappings, classMapped));
				deleteMarker(classMapped);
			}

			Path memberMapped = buildDataCache.resolve("member-mapped.jar");
			if(missing(memberMapped)) {
				this.getLogger().lifecycle("Mapping Members");
				this.execute(system, buildDataCache, MessageFormat.format(info.memberMapCommand, classMapped, memberMappings, memberMapped));
				deleteMarker(memberMapped);
			}

			Path finalMapped = buildDataCache.resolve("final-mapped.jar");
			if(missing(finalMapped)) {
				this.getLogger().lifecycle("Mapping packages/fields");
				this.execute(system,
				             buildDataCache,
				             MessageFormat.format(info.finalMapCommand, memberMapped, accessTransformers, finalMappings, finalMapped));
				deleteMarker(finalMapped);
			}

			if(sources) {
				Path finalClasses = buildDataCache.resolve("final_classes");
				if(missing(finalClasses)) {
					Files.createDirectories(finalClasses);
					this.getLogger().lifecycle("Unzipping mapped jar");
					ZipUtils.unzip(finalMapped, finalClasses, s -> s.startsWith("net/minecraft"));
					deleteMarker(finalClasses);
				}

				Path decompileDir = buildDataCache.resolve("final_sources");
				if(missing(decompileDir)) {
					Files.createDirectories(decompileDir);
					this.getLogger().lifecycle("Decompiling Sources");
					this.execute(system, buildDataCache, MessageFormat.format(info.decompileCommand, finalClasses, decompileDir));
					deleteMarker(decompileDir);
				}

				return decompileDir;
			} else {
				Path strippedServer = buildDataCache.resolve("final-stripped.jar");
				ZipUtils.copyWithout(finalMapped, strippedServer, s -> s.startsWith("net/minecraft"));
				return strippedServer;
			}
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

	public static boolean missing(Path path) throws IOException {
		Path parent = path.getParent();
		Path marker = parent.resolve(path.getFileName() + ".marker");
		if(Files.exists(marker)) {
			delete(path);
			return true;
		} else if(Files.exists(path)) {
			return false;
		} else {
			Files.createDirectories(parent);
			Files.createFile(marker);
			return true;
		}
	}

	public static void deleteMarker(Path path) throws IOException {
		Path marker = path.getParent().resolve(path.getFileName() + ".marker");
		delete(marker);
	}
}
