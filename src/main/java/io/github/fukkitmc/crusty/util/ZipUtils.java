package io.github.fukkitmc.crusty.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ZipUtils {
    public static void unzip(final Path zipFile, final Path decryptTo) {
        try(ZipInputStream zipInputStream = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while((entry = zipInputStream.getNextEntry()) != null) {
                final Path toPath = decryptTo.resolve(entry.getName());
                if(!entry.isDirectory()) {
                    Files.createDirectories(toPath.getParent());
                    Files.copy(zipInputStream, toPath);
                }
            }
        } catch(IOException e) {
            throw new RuntimeException(e);
        }
    }
}