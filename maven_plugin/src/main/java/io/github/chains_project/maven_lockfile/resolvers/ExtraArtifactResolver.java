package io.github.chains_project.maven_lockfile.resolvers;

import io.github.chains_project.maven_lockfile.checksum.AbstractChecksumCalculator;
import io.github.chains_project.maven_lockfile.data.ArtifactId;
import io.github.chains_project.maven_lockfile.data.ArtifactType;
import io.github.chains_project.maven_lockfile.data.Classifier;
import io.github.chains_project.maven_lockfile.data.GroupId;
import io.github.chains_project.maven_lockfile.data.RepositoryId;
import io.github.chains_project.maven_lockfile.data.ResolvedUrl;
import io.github.chains_project.maven_lockfile.data.VersionNumber;
import io.github.chains_project.maven_lockfile.graph.DependencyNode;
import io.github.chains_project.maven_lockfile.reporting.PluginLogManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositoryListener;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * Catch-all resolver that detects artifacts Maven needs during offline builds but that
 * are not captured by the regular dependency graph walk or plugin resolvers.
 *
 * <h3>Mechanism</h3>
 * <ol>
 *   <li><b>{@link #createTracker}</b>: creates a <em>mutable</em> copy of the original
 *       (read-only) Maven session and attaches a {@link RepositoryListener} to it.  The
 *       mutable session is safe to modify because it is freshly allocated.</li>
 *   <li>The caller injects the mutable session into each {@code ProjectBuildingRequest}
 *       before calling {@code DependencyCollectorBuilder.collectDependencyGraph()}.  Every
 *       POM and artifact Maven resolves through those calls fires
 *       {@code artifactResolved} on our listener — covering both warm-cache hits (served
 *       from {@code ~/.m2/repository}) and cold-cache downloads (served from remote).</li>
 *   <li><b>{@link #extractExtras}</b>: applies GAV-level dedup and returns
 *       {@link DependencyNode} entries for the genuinely new extras.</li>
 * </ol>
 */
public class ExtraArtifactResolver {

    // Artifact extensions to capture; skip checksums, signatures, metadata.
    private static final Set<String> ARTIFACT_EXTENSIONS =
            Set.of("jar", "pom", "war", "ear", "rar", "properties", "zip");

    private ExtraArtifactResolver() {}

    // -----------------------------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------------------------

    /**
     * Creates a mutable copy of the Maven repository session and attaches a recording
     * {@link RepositoryListener} to it.
     *
     * <p>The returned {@link Tracker#getMutableSession()} must be injected into every
     * {@code ProjectBuildingRequest} (via {@code setRepositorySession}) before calling
     * {@code collectDependencyGraph}.  This ensures the listener fires for every artifact
     * resolved during normal lockfile generation — no separate re-resolution pass required.
     *
     * @return a {@link Tracker} (never {@code null})
     */
    public static Tracker createTracker(MavenSession mavenSession, MavenProject project) {
        DefaultRepositorySystemSession originalSession =
                (DefaultRepositorySystemSession) mavenSession.getRepositorySession();

        // Create a fresh, MUTABLE copy — setRepositoryListener is allowed on this instance.
        DefaultRepositorySystemSession mutableSession =
                new DefaultRepositorySystemSession(originalSession);

        @SuppressWarnings("deprecation")
        String localRepoBase = mavenSession.getLocalRepository().getBasedir();
        List<RemoteRepository> remoteRepos = buildRemoteRepos(project);
        Map<String, CapturedArtifact> capturedArtifacts = new ConcurrentHashMap<>();

        RepositoryListener existingListener = mutableSession.getRepositoryListener();
        mutableSession.setRepositoryListener(new ChainedRepositoryListener(existingListener) {
            @Override
            public void artifactResolved(RepositoryEvent event) {
                super.artifactResolved(event); // chain to previous listener
                if (event.getException() != null) return;
                Artifact artifact = event.getArtifact();
                if (!ARTIFACT_EXTENSIONS.contains(artifact.getExtension())) return;

                RemoteRepository sourceRepo = null;
                org.eclipse.aether.repository.ArtifactRepository repo = event.getRepository();
                if (repo instanceof RemoteRepository) {
                    // Cold-cache: just downloaded — source repo is directly available.
                    sourceRepo = (RemoteRepository) repo;
                } else if (repo instanceof LocalRepository && event.getFile() != null) {
                    // Warm-cache: served from local cache — look up origin via _remote.repositories.
                    sourceRepo = findOriginRepo(event.getFile(), remoteRepos);
                }

                if (sourceRepo != null) {
                    String key = artifactKey(artifact);
                    capturedArtifacts.putIfAbsent(key,
                            new CapturedArtifact(artifact, event.getFile(), sourceRepo));
                    PluginLogManager.getLog().debug(String.format(
                            "Tracker: captured %s:%s:%s:%s (repo=%s, warm=%b)",
                            artifact.getGroupId(), artifact.getArtifactId(),
                            artifact.getVersion(), artifact.getExtension(),
                            sourceRepo.getId(), repo instanceof LocalRepository));
                }
            }
        });

        PluginLogManager.getLog().debug(
                "Tracker: mutable session created with recording RepositoryListener");
        return new Tracker(mutableSession, capturedArtifacts, remoteRepos, localRepoBase);
    }

    /**
     * Applies GAVT-level dedup to the captured artifacts and returns extra
     * {@link DependencyNode} entries.
     *
     * <p>An artifact is skipped if its {@code groupId:artifactId:version:extension} key already
     * appears in {@code alreadyRecordedGavts}. Using the extension (type) as part of the key
     * allows a conflict-loser JAR ({@code g:a:v:jar}) to be recorded even when its POM
     * ({@code g:a:v:pom}) is already in another lockfile section, and vice-versa.
     *
     * @param tracker              from {@link #createTracker}; returns empty list if {@code null}
     * @param alreadyRecordedGavts {@code groupId:artifactId:version:extension} strings already in lockfile
     * @param checksumCalculator   computes checksums for extra artifact files
     * @return extra {@link DependencyNode} entries, never {@code null}
     */
    public static List<DependencyNode> extractExtras(
            Tracker tracker,
            Set<String> alreadyRecordedGavs,
            AbstractChecksumCalculator checksumCalculator) {
        if (tracker == null) return Collections.emptyList();

        List<DependencyNode> extras = new ArrayList<>();
        for (CapturedArtifact captured : tracker.capturedArtifacts.values()) {
            Artifact artifact = captured.artifact;

            // GAVT-level dedup: include the artifact extension so that a conflict-loser JAR
            // (g:a:v:jar) is not suppressed just because its POM (g:a:v:pom) is already
            // recorded, and vice-versa.
            String gavt = artifact.getGroupId() + ":" + artifact.getArtifactId()
                    + ":" + artifact.getVersion() + ":" + artifact.getExtension();
            if (alreadyRecordedGavs.contains(gavt)) continue;

            String base = captured.sourceRepo.getUrl().endsWith("/")
                    ? captured.sourceRepo.getUrl() : captured.sourceRepo.getUrl() + "/";

            // Derive URL path from local file path relative to localRepoBase.
            String urlPath = "";
            if (captured.file != null) {
                String absPath = captured.file.getAbsolutePath();
                String localBase = tracker.localRepoBase.endsWith(File.separator)
                        ? tracker.localRepoBase : tracker.localRepoBase + File.separator;
                if (absPath.startsWith(localBase)) {
                    urlPath = absPath.substring(localBase.length())
                            .replace(File.separatorChar, '/');
                }
            }
            String resolvedUrl = base + urlPath;

            String checksum = (captured.file != null && captured.file.exists())
                    ? checksumCalculator.calculatePomChecksum(captured.file.toPath())
                    : "";

            String repoId = resolveRepoId(captured.sourceRepo.getUrl(), tracker.remoteRepos);

            DependencyNode node = DependencyNode.ofPlatformArtifact(
                    ArtifactId.of(artifact.getArtifactId()),
                    GroupId.of(artifact.getGroupId()),
                    VersionNumber.of(artifact.getVersion()),
                    Classifier.of(artifact.getClassifier()),
                    ArtifactType.of(artifact.getExtension()),
                    ResolvedUrl.of(resolvedUrl),
                    RepositoryId.of(repoId),
                    checksumCalculator.getChecksumAlgorithm(),
                    checksum);

            extras.add(node);
            PluginLogManager.getLog().debug(String.format(
                    "Tracker: adding %s:%s:%s:%s",
                    artifact.getGroupId(), artifact.getArtifactId(),
                    artifact.getVersion(), artifact.getExtension()));
        }

        PluginLogManager.getLog().info(String.format(
                "Tracker: discovered %d extra artifact(s) not recorded in lockfile",
                extras.size()));
        return extras;
    }

    /**
     * Holds state for a single extra-artifact tracking run.
     */
    public static final class Tracker {
        /** Mutable session copy with the recording {@link RepositoryListener} attached. */
        private final DefaultRepositorySystemSession mutableSession;
        final Map<String, CapturedArtifact> capturedArtifacts;
        final List<RemoteRepository> remoteRepos;
        final String localRepoBase;

        Tracker(
                DefaultRepositorySystemSession mutableSession,
                Map<String, CapturedArtifact> capturedArtifacts,
                List<RemoteRepository> remoteRepos,
                String localRepoBase) {
            this.mutableSession = mutableSession;
            this.capturedArtifacts = capturedArtifacts;
            this.remoteRepos = remoteRepos;
            this.localRepoBase = localRepoBase;
        }

        /**
         * Returns the mutable session to inject into {@code ProjectBuildingRequest}s so that
         * our {@link RepositoryListener} fires during dependency graph collection.
         */
        public DefaultRepositorySystemSession getMutableSession() {
            return mutableSession;
        }

        /**
         * Writes all captured artifacts to a JSON file.
         * Format: JSON array with {@code url}, {@code groupId}, {@code artifactId},
         * {@code version}, {@code classifier}, {@code extension} per entry.
         */
        public void writeToFile(File outputFile) {
            outputFile.getParentFile().mkdirs();
            StringBuilder sb = new StringBuilder("[\n");
            boolean first = true;
            for (CapturedArtifact captured : capturedArtifacts.values()) {
                if (!first) sb.append(",\n");
                first = false;
                Artifact artifact = captured.artifact;
                String base = captured.sourceRepo.getUrl().endsWith("/")
                        ? captured.sourceRepo.getUrl() : captured.sourceRepo.getUrl() + "/";
                String urlPath = "";
                if (captured.file != null) {
                    String absPath = captured.file.getAbsolutePath();
                    String localBase = localRepoBase.endsWith(File.separator)
                            ? localRepoBase : localRepoBase + File.separator;
                    if (absPath.startsWith(localBase)) {
                        urlPath = absPath.substring(localBase.length())
                                .replace(File.separatorChar, '/');
                    }
                }
                sb.append("  {\n");
                sb.append("    \"url\": \"").append(esc(base + urlPath)).append("\",\n");
                sb.append("    \"groupId\": \"").append(esc(artifact.getGroupId())).append("\",\n");
                sb.append("    \"artifactId\": \"").append(esc(artifact.getArtifactId())).append("\",\n");
                sb.append("    \"version\": \"").append(esc(artifact.getVersion())).append("\",\n");
                sb.append("    \"classifier\": \"").append(esc(artifact.getClassifier())).append("\",\n");
                sb.append("    \"extension\": \"").append(esc(artifact.getExtension())).append("\"\n");
                sb.append("  }");
            }
            sb.append("\n]");
            try {
                Files.writeString(outputFile.toPath(), sb.toString());
                PluginLogManager.getLog().info(String.format(
                        "Tracker: wrote %d artifact(s) to %s", capturedArtifacts.size(), outputFile));
            } catch (IOException e) {
                PluginLogManager.getLog().warn(
                        "Tracker: could not write tracker file " + outputFile + ": " + e.getMessage());
            }
        }

        private static String esc(String s) {
            return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    private static final class CapturedArtifact {
        final Artifact artifact;
        final File file;
        final RemoteRepository sourceRepo;

        CapturedArtifact(Artifact artifact, File file, RemoteRepository sourceRepo) {
            this.artifact = artifact;
            this.file = file;
            this.sourceRepo = sourceRepo;
        }
    }

    /**
     * Delegates every {@link RepositoryListener} event to a wrapped delegate.
     * Subclasses override only the methods they need to augment.
     */
    private abstract static class ChainedRepositoryListener extends AbstractRepositoryListener {
        private final RepositoryListener delegate;

        ChainedRepositoryListener(RepositoryListener delegate) {
            this.delegate = delegate;
        }

        @Override public void artifactDeployed(RepositoryEvent e) { if (delegate != null) delegate.artifactDeployed(e); }
        @Override public void artifactDeploying(RepositoryEvent e) { if (delegate != null) delegate.artifactDeploying(e); }
        @Override public void artifactDescriptorInvalid(RepositoryEvent e) { if (delegate != null) delegate.artifactDescriptorInvalid(e); }
        @Override public void artifactDescriptorMissing(RepositoryEvent e) { if (delegate != null) delegate.artifactDescriptorMissing(e); }
        @Override public void artifactDownloaded(RepositoryEvent e) { if (delegate != null) delegate.artifactDownloaded(e); }
        @Override public void artifactDownloading(RepositoryEvent e) { if (delegate != null) delegate.artifactDownloading(e); }
        @Override public void artifactInstalled(RepositoryEvent e) { if (delegate != null) delegate.artifactInstalled(e); }
        @Override public void artifactInstalling(RepositoryEvent e) { if (delegate != null) delegate.artifactInstalling(e); }
        @Override public void artifactResolved(RepositoryEvent e) { if (delegate != null) delegate.artifactResolved(e); }
        @Override public void artifactResolving(RepositoryEvent e) { if (delegate != null) delegate.artifactResolving(e); }
        @Override public void metadataDeployed(RepositoryEvent e) { if (delegate != null) delegate.metadataDeployed(e); }
        @Override public void metadataDeploying(RepositoryEvent e) { if (delegate != null) delegate.metadataDeploying(e); }
        @Override public void metadataDownloaded(RepositoryEvent e) { if (delegate != null) delegate.metadataDownloaded(e); }
        @Override public void metadataDownloading(RepositoryEvent e) { if (delegate != null) delegate.metadataDownloading(e); }
        @Override public void metadataInstalled(RepositoryEvent e) { if (delegate != null) delegate.metadataInstalled(e); }
        @Override public void metadataInstalling(RepositoryEvent e) { if (delegate != null) delegate.metadataInstalling(e); }
        @Override public void metadataInvalid(RepositoryEvent e) { if (delegate != null) delegate.metadataInvalid(e); }
        @Override public void metadataResolved(RepositoryEvent e) { if (delegate != null) delegate.metadataResolved(e); }
        @Override public void metadataResolving(RepositoryEvent e) { if (delegate != null) delegate.metadataResolving(e); }
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Reads Maven's {@code _remote.repositories} tracking file to find which remote repository
     * originally provided the given locally cached artifact.
     *
     * <p>File format (Java Properties): keys are {@code artifactFileName>repoId}, value is empty.
     */
    private static RemoteRepository findOriginRepo(
            File artifactFile, List<RemoteRepository> remoteRepos) {
        File trackingFile = new File(artifactFile.getParentFile(), "_remote.repositories");
        if (!trackingFile.exists()) return null;
        try {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(trackingFile)) {
                props.load(fis);
            }
            String prefix = artifactFile.getName() + ">";
            for (String key : props.stringPropertyNames()) {
                if (key.startsWith(prefix)) {
                    String repoId = key.substring(prefix.length());
                    for (RemoteRepository repo : remoteRepos) {
                        if (repo.getId().equals(repoId)) return repo;
                    }
                }
            }
        } catch (IOException e) {
            PluginLogManager.getLog().debug(
                    "Tracker: could not read _remote.repositories for "
                            + artifactFile + ": " + e.getMessage());
        }
        return null;
    }

    private static String artifactKey(Artifact artifact) {
        return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":"
                + artifact.getVersion() + ":" + artifact.getExtension() + ":"
                + artifact.getClassifier();
    }

    private static List<RemoteRepository> buildRemoteRepos(MavenProject project) {
        List<RemoteRepository> repos = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (ArtifactRepository repo : project.getRemoteArtifactRepositories()) {
            if (seenIds.add(repo.getId())) {
                repos.add(new RemoteRepository.Builder(
                        repo.getId(), "default", repo.getUrl()).build());
            }
        }
        for (ArtifactRepository repo : project.getPluginArtifactRepositories()) {
            if (seenIds.add(repo.getId())) {
                repos.add(new RemoteRepository.Builder(
                        repo.getId(), "default", repo.getUrl()).build());
            }
        }
        return repos;
    }

    private static String resolveRepoId(String repoUrl, List<RemoteRepository> remoteRepos) {
        if (repoUrl == null) return "unknown";
        for (RemoteRepository repo : remoteRepos) {
            if (repoUrl.startsWith(repo.getUrl())) return repo.getId();
        }
        return "unknown";
    }
}
