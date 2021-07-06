package io.github.fukkitmc.crusty.mappings;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.lang.model.SourceVersion;

import com.google.common.collect.Iterables;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import io.github.fukkitmc.crusty.CrustyExtension;
import io.github.fukkitmc.crusty.util.CachedFile;
import org.gradle.api.artifacts.Dependency;
import org.objectweb.asm.commons.Remapper;

import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.format.Tiny2Reader;
import net.fabricmc.mappingio.format.Tiny2Writer;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public class CrustyMappings extends AbstractSelfResolvingDependency {
	public final Dependency intermediary;
	public final Iterable<File> classes;
	public final Iterable<File> members;
	public final CrustyExtension extension;
	public final CrustyFile file;

	public CrustyMappings(CrustyExtension plugin, String version, Dependency intermediary, Iterable<File> classes, Iterable<File> members) {
		super(plugin.project, "org.spigotmc", "crusty-mappigs", version);
		this.extension = plugin;
		this.intermediary = intermediary;
		this.classes = classes;
		this.members = members;
		this.file = new CrustyFile(plugin.project.getBuildDir().toPath().resolve("crusty.jar")); // todo maybe make this project cached cus lazy
	}

	public static String deobf(Map<String, String> classes, String name) {
		int current = name.length();
		do {
			String sub = name.substring(0, current);
			String outerMapped = classes.get(sub);
			if(outerMapped != null) {
				return outerMapped + name.substring(current);
			}
			current = name.lastIndexOf('$', current - 1);
		} while(current != -1);
		return name;
	}

	public static String hash(Iterable<File> files) {
		Hasher hasher = Hashing.sha256().newHasher();
		try {
			hash(hasher, files);
		} catch(IOException e) {
			throw new RuntimeException(e);
		}
		return hasher.hash().toString();
	}

	public static void hash(Hasher hasher, Iterable<File> files) throws IOException {
		for(File file : files) {
			hasher.putUnencodedChars(file.getAbsolutePath());
			hasher.putLong(file.lastModified());
		}
	}

	@Override
	protected Path resolvePaths() {
		return this.file.getPath();
	}

	@Override
	public Dependency copy() {
		return new CrustyMappings(this.extension, this.version, intermediary, classes, members);
	}

	public class CrustyFile extends CachedFile<String> {
		public CrustyFile(Path file) {
			super(file, String.class);
		}

		@Override
		protected String writeIfOutdated(Path path, String currentData) {
			Iterable<File> intermediaryFiles = CrustyMappings.this.resolve(List.of(CrustyMappings.this.intermediary));
			String hash = hash(Iterables.concat(CrustyMappings.this.classes, CrustyMappings.this.members, intermediaryFiles));
			if(hash.equals(currentData)) {
				return null;
			}

			try {
				MemoryMappingTree intermediary = new MemoryMappingTree();
				try(FileSystem system = FileSystems.newFileSystem(Iterables.getOnlyElement(intermediaryFiles).toPath())) {
					try(BufferedReader reader = Files.newBufferedReader(system.getPath("mappings/mappings.tiny"))) {
						Tiny2Reader.read(reader, intermediary);
					}
				}

				record MemberEntry(String name, String desc) {}
				record FromToEntry(MemberEntry entry, String destName, boolean isMethod) {}
				record ClassEntry(String destName, List<FromToEntry> entries) {}

				Map<String, ClassEntry> mappings = new HashMap<>();
				Map<String, String> reversedClasses = new HashMap<>(); // named -> obf
				for(File cls : CrustyMappings.this.classes) {
					try(BufferedReader reader = Files.newBufferedReader(cls.toPath())) {
						String ln;
						while((ln = reader.readLine()) != null) {
							if(ln.isEmpty() || ln.charAt(0) == '#') {
								continue;
							}

							String[] split = ln.split(" ");
							mappings.put(split[0], new ClassEntry(split[1], new ArrayList<>()));
							reversedClasses.put(split[1], split[0]);
						}
					}
				}

				Remapper remapper = new Remapper() {
					@Override
					public String map(String internalName) {
						return reversedClasses.getOrDefault(internalName, internalName);
					}
				};

				for(File member : CrustyMappings.this.members) {
					try(BufferedReader reader = Files.newBufferedReader(member.toPath())) {
						String ln;
						while((ln = reader.readLine()) != null) {
							if(ln.isEmpty() || ln.charAt(0) == '#') {
								continue;
							}

							String[] split = ln.split(" ");
							if(split.length == 3) { // fields
								String obfName = deobf(reversedClasses, split[0]);
								ClassEntry entry = mappings.computeIfAbsent(obfName, $ -> new ClassEntry($, new ArrayList<>()));
								MappingTree.FieldMapping mapping = intermediary.getField(obfName, split[1], null);
								if(mapping == null) {
									String newName = split[1].replace("_", "");
									if(SourceVersion.isKeyword(newName)) {
										mapping = intermediary.getField(obfName, newName, null);
									}
								}
								if(mapping == null) {
									System.out.println("Unable to find descriptor for (" + obfName + "/" + split[0] + ").(" + split[1] + "/" + split[2] + ")");
								}
								String fieldDesc = mapping.getSrcDesc();
								entry.entries.add(new FromToEntry(new MemberEntry(split[1], fieldDesc), split[2], false));
							} else if(split.length == 4) { // methods
								ClassEntry entry = mappings.computeIfAbsent(reversedClasses.getOrDefault(split[0], split[0]),
								                                            $ -> new ClassEntry($, new ArrayList<>()));

								entry.entries.add(new FromToEntry(new MemberEntry(split[1], remapper.mapMethodDesc(split[2])), split[3], true));
							}
						}
					}
				}

				try(ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(path))) {
					zos.putNextEntry(new ZipEntry("mappings/mappings.tiny"));
					OutputStreamWriter writer = new OutputStreamWriter(zos);
					Tiny2Writer tinyWriter = new Tiny2Writer(writer, false);
					tinyWriter.visitHeader();
					tinyWriter.visitContent();
					tinyWriter.visitNamespaces("intermediary", List.of("named"));
					for(Map.Entry<String, ClassEntry> entry : mappings.entrySet()) {
						String obfClassName = entry.getKey();
						ClassEntry value = entry.getValue();
						tinyWriter.visitContent();
						tinyWriter.visitClass(intermediary.mapClassName(obfClassName, 0));
						tinyWriter.visitDstName(MappedElementKind.CLASS, 0, value.destName());
						tinyWriter.visitElementContent(MappedElementKind.CLASS);

						for(FromToEntry fromToEntry : value.entries) {
							if(fromToEntry.isMethod) {
								MappingTree.MethodMapping method = intermediary.getMethod(obfClassName, fromToEntry.entry.name, fromToEntry.entry.desc);
								if(method == null) continue;
								tinyWriter.visitMethod(method.getSrcName(), method.getSrcDesc());
								tinyWriter.visitDstName(MappedElementKind.METHOD, 0, fromToEntry.destName);
								tinyWriter.visitElementContent(MappedElementKind.METHOD);
							} else {
								MappingTree.FieldMapping field = intermediary.getField(obfClassName, fromToEntry.entry.name, fromToEntry.entry.desc);
								if(field == null) continue;
								tinyWriter.visitField(field.getSrcName(), field.getSrcDesc());
								tinyWriter.visitDstName(MappedElementKind.FIELD, 0, fromToEntry.destName);
								tinyWriter.visitElementContent(MappedElementKind.FIELD);
							}
						}
					}
					tinyWriter.visitEnd();
					writer.flush();
					zos.closeEntry();
				}
			} catch(IOException e) {
				throw new RuntimeException(e);
			}

			return hash;
		}
	}
}
