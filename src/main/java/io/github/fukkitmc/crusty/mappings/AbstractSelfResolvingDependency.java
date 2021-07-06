package io.github.fukkitmc.crusty.mappings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.Iterables;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.FileCollectionDependency;
import org.gradle.api.artifacts.SelfResolvingDependency;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.artifacts.dependencies.AbstractDependency;
import org.gradle.api.internal.artifacts.dependencies.SelfResolvingDependencyInternal;
import org.gradle.api.internal.tasks.AbstractTaskDependency;
import org.gradle.api.internal.tasks.TaskDependencyResolveContext;
import org.gradle.api.tasks.TaskDependency;

public abstract class AbstractSelfResolvingDependency extends AbstractDependency
		implements FileCollectionDependency, SelfResolvingDependencyInternal {
	public final Project project;
	protected final String group, name, version;
	protected Set<File> resolved;

	public AbstractSelfResolvingDependency(Project project, String group, String name, String version) {
		this.project = project;
		this.group = group;
		this.name = name;
		this.version = version;
	}

	@Override
	public TaskDependency getBuildDependencies() {
		return new AbstractTaskDependency() {
			@Override
			public String toString() {
				return "Dependencies of " + AbstractSelfResolvingDependency.this.toString();
			}

			@Override
			public void visitDependencies(TaskDependencyResolveContext context) {
				context.add(AbstractSelfResolvingDependency.this);
			}
		};
	}

	@Nullable
	@Override
	public String getGroup() {
		return this.group;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Nullable
	@Override
	public String getVersion() {
		return this.version;
	}

	@Override
	public boolean contentEquals(Dependency dependency) {
		return this.equals(dependency);
	}

	@Override
	public Set<File> resolve() {
		return this.resolve(true);
	}

	protected Set<File> path() throws IOException {
		if (this.resolved == null) {
			Set<File> files = new HashSet<>();
			files.add(this.resolvePaths().toFile());
			this.resolved = files;
		}
		return this.resolved;
	}

	protected abstract Path resolvePaths();

	@Override
	public Set<File> resolve(boolean b) {
		try {
			return this.path();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public FileCollection getFiles() {
		try {
			return this.project.files(this.path());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Nullable
	@Override
	public ComponentIdentifier getTargetComponentId() {
		return this::toString;
	}

	@Override
	public int hashCode() {
		int result = this.project != null ? this.project.hashCode() : 0;
		result = 31 * result + (this.group != null ? this.group.hashCode() : 0);
		result = 31 * result + (this.name != null ? this.name.hashCode() : 0);
		result = 31 * result + (this.version != null ? this.version.hashCode() : 0);
		result = 31 * result + (this.resolved != null ? this.resolved.hashCode() : 0);
		return result;
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (!(object instanceof AbstractSelfResolvingDependency)) {
			return false;
		}

		AbstractSelfResolvingDependency that = (AbstractSelfResolvingDependency) object;

		if (!Objects.equals(this.project, that.project)) {
			return false;
		}
		if (!Objects.equals(this.group, that.group)) {
			return false;
		}
		if (!Objects.equals(this.name, that.name)) {
			return false;
		}
		if (!Objects.equals(this.version, that.version)) {
			return false;
		}
		return Objects.equals(this.resolved, that.resolved);
	}

	@Override
	public String toString() {
		return this.getClass().getSimpleName();
	}

	public Iterable<File> resolve(Iterable<Dependency> dependencies) {
		return resolve(this.project, dependencies);
	}

	public static Iterable<File> resolve(Project project, Iterable<Dependency> dependencies) {
		Configuration configuration = null;
		Iterable<File> selfResolving = null;
		for (Dependency dependency : dependencies) {
			if (dependency instanceof SelfResolvingDependency) {
				if (selfResolving == null) {
					selfResolving = ((SelfResolvingDependency) dependency).resolve();
				} else {
					selfResolving = Iterables.concat(((SelfResolvingDependency) dependency).resolve(), selfResolving);
				}
			} else {
				if (configuration == null) {
					configuration = project.getConfigurations().detachedConfiguration(dependency);
				} else {
					configuration.getDependencies().add(dependency);
				}
			}
		}

		if (configuration == null) {
			if (selfResolving == null) {
				return Collections.emptyList();
			} else {
				return selfResolving;
			}
		} else {
			if (selfResolving == null) {
				return configuration;
			} else {
				return Iterables.concat(configuration.getResolvedConfiguration().getFiles(), selfResolving);
			}
		}
	}
}