package io.github.chains_project.maven_lockfile.checksum;

import com.google.common.io.BaseEncoding;
import io.github.chains_project.maven_lockfile.reporting.PluginLogManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Locale;
import org.apache.maven.artifact.Artifact;

public abstract class AbstractChecksumCalculator {

    protected String checksumAlgorithm;

    AbstractChecksumCalculator(String checksumAlgorithm) {
        if (checksumAlgorithm == null || checksumAlgorithm.isEmpty()) {
            this.checksumAlgorithm = getDefaultChecksumAlgorithm();
        } else {
            this.checksumAlgorithm = checksumAlgorithm;
        }
    }

    /**
     * @return the checksumAlgorithm
     */
    public String getChecksumAlgorithm() {
        return checksumAlgorithm;
    }

    public void prewarmArtifactCache(Collection<Artifact> artifacts) {
        // no-op by default; override in remote implementations for parallel pre-warming
    }

    public void prewarmPluginCache(Collection<Artifact> artifacts) {
        // no-op by default; override in remote implementations for parallel pre-warming
    }

    public abstract String calculateArtifactChecksum(Artifact artifact);

    public abstract String calculatePluginChecksum(Artifact artifact);

    public abstract String getDefaultChecksumAlgorithm();

    public abstract RepositoryInformation getArtifactResolvedField(Artifact artifact);

    public abstract RepositoryInformation getPluginResolvedField(Artifact artifact);

    public String calculatePomChecksum(Path path) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(checksumAlgorithm);
            byte[] fileBuffer = Files.readAllBytes(path);
            byte[] artifactHash = messageDigest.digest(fileBuffer);
            BaseEncoding baseEncoding = BaseEncoding.base16();
            return baseEncoding.encode(artifactHash).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            PluginLogManager.getLog().warn("Could not calculate checksum for pom " + path, e);
            return "";
        }
    }
}
