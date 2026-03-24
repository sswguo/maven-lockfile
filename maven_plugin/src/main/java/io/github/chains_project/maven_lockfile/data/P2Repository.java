package io.github.chains_project.maven_lockfile.data;

import java.util.Objects;

/**
 * Represents a P2 repository referenced by an Eclipse Target Platform file.
 *
 * <p>Records the metadata files ({@code content.jar} and {@code artifacts.jar}) that
 * hermeto must download alongside the artifact JARs. These files are required by Tycho
 * to treat the local directory as a valid offline P2 repository.
 *
 * <p>The {@code localPath} field gives the relative path within {@code hermeto-output/deps/}
 * where all files for this repository are stored, and is used to generate the
 * {@code file://} URL in the hermeto-injected {@code settings.xml} mirror configuration.
 */
public class P2Repository implements Comparable<P2Repository> {

    /** Original P2 repository URL from the .target file. */
    private final String originalUrl;

    /** URL to download content.jar (IU dependency metadata). */
    private final String contentJarUrl;

    /** SHA-256 checksum of content.jar. */
    private final String contentJarChecksum;

    /** URL to download artifacts.jar (artifact download mappings). */
    private final String artifactsJarUrl;

    /** SHA-256 checksum of artifacts.jar. */
    private final String artifactsJarChecksum;

    /**
     * Relative path within hermeto-output/deps/ for this repo's files,
     * e.g. "p2/jdtls-1.51.0". Used to construct the file:// mirror URL.
     */
    private final String localPath;

    public P2Repository(
            String originalUrl,
            String contentJarUrl,
            String contentJarChecksum,
            String artifactsJarUrl,
            String artifactsJarChecksum,
            String localPath) {
        this.originalUrl = originalUrl;
        this.contentJarUrl = contentJarUrl;
        this.contentJarChecksum = contentJarChecksum;
        this.artifactsJarUrl = artifactsJarUrl;
        this.artifactsJarChecksum = artifactsJarChecksum;
        this.localPath = localPath;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public String getContentJarUrl() {
        return contentJarUrl;
    }

    public String getContentJarChecksum() {
        return contentJarChecksum;
    }

    public String getArtifactsJarUrl() {
        return artifactsJarUrl;
    }

    public String getArtifactsJarChecksum() {
        return artifactsJarChecksum;
    }

    public String getLocalPath() {
        return localPath;
    }

    @Override
    public int compareTo(P2Repository o) {
        return this.originalUrl.compareTo(o.originalUrl);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof P2Repository)) return false;
        return Objects.equals(originalUrl, ((P2Repository) obj).originalUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(originalUrl);
    }

    @Override
    public String toString() {
        return "P2Repository[" + originalUrl + " -> " + localPath + "]";
    }
}
