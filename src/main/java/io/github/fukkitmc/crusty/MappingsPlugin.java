package io.github.fukkitmc.crusty;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class MappingsPlugin implements Plugin<Project> {
	@Override
	public void apply(Project target) {
		target.getExtensions().create("fukkit", MappingsExtension.class, target);
		target.getExtensions().create("crusty", CrustyExtension.class, target);
	}
}
