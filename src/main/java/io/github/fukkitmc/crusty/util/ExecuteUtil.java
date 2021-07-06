package io.github.fukkitmc.crusty.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public class ExecuteUtil {
	public static void execute(Path jar, String[] args) throws IOException {
		try(URLClassLoader classLoader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, ClassLoader.getSystemClassLoader())) {
			String mainClass;
			try(FileSystem system = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
				try(InputStream input = Files.newInputStream(system.getPath(JarFile.MANIFEST_NAME))) {
					Manifest manifest = new Manifest(input);
					mainClass = manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
				}
			}

			try {
				Class<?> type = Class.forName(mainClass, false, classLoader);
				Method main = type.getDeclaredMethod("main", String[].class);
				main.invoke(null, (Object) args);
			} catch(Throwable e) {
				throw new RuntimeException(e);
			}
		}
	}
}
