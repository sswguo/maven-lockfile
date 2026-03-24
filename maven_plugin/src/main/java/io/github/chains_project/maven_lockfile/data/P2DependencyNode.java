package io.github.chains_project.maven_lockfile.data;

import java.util.Objects;

/**
 * Represents a single P2/OSGi artifact resolved from an Eclipse Target Platform.
 *
 * <p>P2 artifacts are identified by a classifier (e.g. "osgi.bundle", "org.eclipse.update.feature"),
 * an installable unit ID, and a version. The download URL and checksum are recorded so that
 * hermeto can pre-fetch the artifact and the lockfile can verify it offline.
 */
public class P2DependencyNode implements Comparable<P2DependencyNode> {

    /** P2 installable unit classifier, e.g. "osgi.bundle" or "org.eclipse.update.feature". */
    private final String classifier;

    /** P2 installable unit ID, e.g. "org.eclipse.jdt.ls.core". */
    private final String id;

    /** Full P2 version string, e.g. "1.51.0.202312211634". */
    private final String version;

    /** Direct download URL for the artifact JAR/zip. */
    private final String downloadUrl;

    /** P2 repository URL this artifact was resolved from. */
    private final String repositoryUrl;

    /** Relative path within the P2 mirror directory, e.g. "plugins/org.eclipse.jdt.ls.core_1.51.0.jar". */
    private final String mirrorPath;

    private final String checksumAlgorithm;
    private final String checksum;

    public P2DependencyNode(
            String classifier,
            String id,
            String version,
            String downloadUrl,
            String repositoryUrl,
            String mirrorPath,
            String checksumAlgorithm,
            String checksum) {
        this.classifier = classifier;
        this.id = id;
        this.version = version;
        this.downloadUrl = downloadUrl;
        this.repositoryUrl = repositoryUrl;
        this.mirrorPath = mirrorPath;
        this.checksumAlgorithm = checksumAlgorithm;
        this.checksum = checksum;
    }

    public String getClassifier() {
        return classifier;
    }

    public String getId() {
        return id;
    }

    public String getVersion() {
        return version;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    public String getMirrorPath() {
        return mirrorPath;
    }

    public String getChecksumAlgorithm() {
        return checksumAlgorithm;
    }

    public String getChecksum() {
        return checksum;
    }

    @Override
    public int compareTo(P2DependencyNode o) {
        int cmp = this.id.compareTo(o.id);
        if (cmp != 0) return cmp;
        cmp = this.version.compareTo(o.version);
        if (cmp != 0) return cmp;
        return this.classifier.compareTo(o.classifier);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof P2DependencyNode)) return false;
        P2DependencyNode other = (P2DependencyNode) obj;
        return Objects.equals(classifier, other.classifier)
                && Objects.equals(id, other.id)
                && Objects.equals(version, other.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(classifier, id, version);
    }

    @Override
    public String toString() {
        return classifier + ":" + id + ":" + version;
    }
}
