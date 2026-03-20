package io.github.chains_project.maven_lockfile.data;

import io.github.chains_project.maven_lockfile.graph.DependencyNode;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public class Extension implements Comparable<Extension> {

    private final GroupId groupId;
    private final ArtifactId artifactId;
    private final VersionNumber version;
    private final String relativePath;
    private final ResolvedUrl resolved;
    private final RepositoryId repositoryId;
    private final String checksumAlgorithm;
    private final String checksum;
    private final Set<DependencyNode> dependencies;
    private final Pom pom;
    private final Set<Pom> boms;

    public Extension(GroupId groupId, ArtifactId artifactId, VersionNumber version, String relativePath, ResolvedUrl resolved, RepositoryId repositoryId, String checksumAlgorithm, String checksum) {
        this(groupId, artifactId, version, relativePath, resolved, repositoryId, checksumAlgorithm, checksum, null, null);
    }

    public Extension(GroupId groupId, ArtifactId artifactId, VersionNumber version, String relativePath, ResolvedUrl resolved, RepositoryId repositoryId, String checksumAlgorithm, String checksum, Set<DependencyNode> dependencies) {
        this(groupId, artifactId, version, relativePath, resolved, repositoryId, checksumAlgorithm, checksum, dependencies, null);
    }

    public Extension(GroupId groupId, ArtifactId artifactId, VersionNumber version, String relativePath, ResolvedUrl resolved, RepositoryId repositoryId, String checksumAlgorithm, String checksum, Set<DependencyNode> dependencies, Pom pom) {
        this(groupId, artifactId, version, relativePath, resolved, repositoryId, checksumAlgorithm, checksum, dependencies, pom, null);
    }

    public Extension(GroupId groupId, ArtifactId artifactId, VersionNumber version, String relativePath, ResolvedUrl resolved, RepositoryId repositoryId, String checksumAlgorithm, String checksum, Set<DependencyNode> dependencies, Pom pom, Set<Pom> boms) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.relativePath = relativePath;
        this.resolved = resolved;
        this.repositoryId = repositoryId;
        this.checksumAlgorithm = checksumAlgorithm;
        this.checksum = checksum;
        this.dependencies = dependencies == null ? Collections.emptySet() : dependencies;
        this.pom = pom;
        this.boms = boms == null ? Collections.emptySet() : new TreeSet<>(boms);
    }

    public GroupId getGroupId() {
        return groupId;
    }

    public ArtifactId getArtifactId() {
        return artifactId;
    }

    public VersionNumber getVersion() {
        return version;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public ResolvedUrl getResolved() {
        return resolved;
    }

    public RepositoryId getRepositoryId() {
        return repositoryId;
    }

    public String getChecksumAlgorithm() {
        return checksumAlgorithm;
    }

    public String getChecksum() {
        return checksum;
    }

    public Set<DependencyNode> getDependencies() {
        return dependencies;
    }

    public Pom getPom() {
        return pom;
    }

    public Set<Pom> getBoms() {
        return boms;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Extension extension = (Extension) o;
        return Objects.equals(groupId, extension.groupId) && Objects.equals(artifactId, extension.artifactId) && Objects.equals(version, extension.version) && Objects.equals(relativePath, extension.relativePath) && Objects.equals(resolved, extension.resolved) && Objects.equals(repositoryId, extension.repositoryId) && Objects.equals(checksumAlgorithm, extension.checksumAlgorithm) && Objects.equals(checksum, extension.checksum);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, artifactId, version, relativePath, resolved, repositoryId, checksumAlgorithm, checksum);
    }

    @Override
    public int compareTo(Extension o) {
        return 0;
    }
}
