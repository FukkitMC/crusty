package io.github.fukkitmc.crusty.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ZipUtils {
    public static void unzip(final Path zipFile, final Path decryptTo, Predicate<String> shouldCopy) {
        try(ZipInputStream zipInputStream = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while((entry = zipInputStream.getNextEntry()) != null) {
                final Path toPath = decryptTo.resolve(entry.getName());
                if(!entry.isDirectory() && shouldCopy.test(entry.getName())) {
                    Files.createDirectories(toPath.getParent());
                    Files.copy(zipInputStream, toPath);
                }
            }
        } catch(IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void copyWithout(final Path zipFile, final Path decryptTo, Predicate<String> shouldCopy) {
        byte[] buf = new byte[4096];
        try(ZipInputStream input = new ZipInputStream(Files.newInputStream(zipFile)); ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(decryptTo))) {
            ZipEntry entry;
            while((entry = input.getNextEntry()) != null) {
                if(shouldCopy.test(entry.getName())) {
                    output.putNextEntry(new ZipEntry(entry.getName()));
                    int read;
                    while((read = input.read(buf)) != -1) {
                        output.write(buf, 0, read);
                    }
                    output.closeEntry();
                }
            }
        } catch(IOException e) {
            throw new RuntimeException(e);
        }
    }
}