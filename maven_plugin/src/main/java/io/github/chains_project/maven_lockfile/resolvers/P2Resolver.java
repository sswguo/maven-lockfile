package io.github.chains_project.maven_lockfile.resolvers;

import io.github.chains_project.maven_lockfile.data.P2DependencyNode;
import io.github.chains_project.maven_lockfile.data.P2Repository;
import io.github.chains_project.maven_lockfile.reporting.PluginLogManager;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.maven.project.MavenProject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Resolves P2/OSGi dependencies from Eclipse Target Platform (.target) files.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Detect Tycho project (presence of {@code tycho-maven-plugin} in build plugins).
 *   <li>Find all {@code *.target} files in the project base directory.
 *   <li>Parse each {@code .target} file to extract P2 repository URLs and required
 *       installable units (IUs).
 *   <li>For each P2 repository, download {@code content.jar} and {@code artifacts.jar},
 *       parse the embedded XML to build an IU dependency graph and an artifact mapping.
 *   <li>Perform BFS from the required IUs to compute the full transitive closure.
 *   <li>For each resolved IU, look up the download URL and checksum from the artifacts
 *       metadata, then emit a {@link P2DependencyNode}.
 * </ol>
 *
 * <p>This implementation does NOT embed an OSGi/Equinox runtime — it works with plain
 * Java HTTP and XML parsing, avoiding the classloader complexity of Tycho internals.
 */
public class P2Resolver {

    private static final String TYCHO_PLUGIN_ARTIFACT_ID = "tycho-maven-plugin";
    private static final String SHA256 = "SHA-256";
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 120_000;

    /** Result of P2 resolution — both the resolved artifacts and the repo metadata. */
    public static class P2ResolverResult {
        private final List<P2DependencyNode> artifacts;
        private final List<P2Repository> repositories;

        public P2ResolverResult(List<P2DependencyNode> artifacts, List<P2Repository> repositories) {
            this.artifacts = artifacts;
            this.repositories = repositories;
        }

        public List<P2DependencyNode> getArtifacts() {
            return artifacts;
        }

        public List<P2Repository> getRepositories() {
            return repositories;
        }
    }

    /**
     * Returns true if the given project uses Tycho (i.e. it is an Eclipse/OSGi project
     * managed by the Tycho Maven plugin).
     */
    public static boolean isTychoProject(MavenProject project) {
        return project.getBuildPlugins().stream()
                .anyMatch(p -> TYCHO_PLUGIN_ARTIFACT_ID.equals(p.getArtifactId()));
    }

    /**
     * Resolves all P2 artifacts and repository metadata required by the project's
     * Eclipse Target Platform files.
     *
     * @param project the Maven project
     * @return result containing resolved P2 artifacts and repo metadata
     */
    public static P2ResolverResult resolve(MavenProject project) {
        List<P2DependencyNode> artifacts = new ArrayList<>();
        List<P2Repository> repositories = new ArrayList<>();

        File[] targetFiles = project.getBasedir().listFiles(
                f -> f.isFile() && f.getName().endsWith(".target"));
        if (targetFiles == null || targetFiles.length == 0) {
            PluginLogManager.getLog().info("No .target files found in " + project.getBasedir());
            return new P2ResolverResult(artifacts, repositories);
        }

        for (File targetFile : targetFiles) {
            PluginLogManager.getLog().info("Resolving P2 deps from target file: " + targetFile.getName());
            try {
                P2ResolverResult fileResult = resolveTargetFile(targetFile);
                artifacts.addAll(fileResult.getArtifacts());
                // deduplicate repositories by originalUrl
                for (P2Repository repo : fileResult.getRepositories()) {
                    if (repositories.stream().noneMatch(r -> r.getOriginalUrl().equals(repo.getOriginalUrl()))) {
                        repositories.add(repo);
                    }
                }
            } catch (Exception e) {
                PluginLogManager.getLog().warn("Failed to resolve P2 deps from "
                        + targetFile.getName() + ": " + e.getMessage());
            }
        }
        return new P2ResolverResult(artifacts, repositories);
    }

    // -------------------------------------------------------------------------
    // Target file parsing
    // -------------------------------------------------------------------------

    private static P2ResolverResult resolveTargetFile(File targetFile) throws Exception {
        Document doc = parseXml(targetFile);
        List<P2DependencyNode> artifacts = new ArrayList<>();
        List<P2Repository> repositories = new ArrayList<>();

        // Each <location type="InstallableUnit"> has one or more <repository> and <unit> elements
        NodeList locations = doc.getElementsByTagName("location");
        for (int i = 0; i < locations.getLength(); i++) {
            Element location = (Element) locations.item(i);
            if (!"InstallableUnit".equals(location.getAttribute("type"))) {
                continue;
            }

            // Collect repository URLs for this location
            List<String> repoUrls = new ArrayList<>();
            NodeList repos = location.getElementsByTagName("repository");
            for (int j = 0; j < repos.getLength(); j++) {
                repoUrls.add(((Element) repos.item(j)).getAttribute("location"));
            }

            // Collect required IU ids
            Set<String> requiredIus = new HashSet<>();
            NodeList units = location.getElementsByTagName("unit");
            for (int j = 0; j < units.getLength(); j++) {
                requiredIus.add(((Element) units.item(j)).getAttribute("id"));
            }

            for (String repoUrl : repoUrls) {
                try {
                    P2ResolverResult repoResult = resolveFromRepository(repoUrl, requiredIus);
                    artifacts.addAll(repoResult.getArtifacts());
                    repositories.addAll(repoResult.getRepositories());
                } catch (Exception e) {
                    PluginLogManager.getLog().warn("Failed to resolve from P2 repo " + repoUrl + ": " + e.getMessage());
                }
            }
        }
        return new P2ResolverResult(artifacts, repositories);
    }

    // -------------------------------------------------------------------------
    // P2 repository resolution
    // -------------------------------------------------------------------------

    /**
     * Downloads and parses the P2 repository metadata (content.jar + artifacts.jar),
     * resolves the transitive closure of the required IUs, and returns the artifact list
     * plus the repository metadata entry for hermeto to download.
     */
    private static P2ResolverResult resolveFromRepository(
            String repoUrl, Set<String> requiredIuIds) throws Exception {

        PluginLogManager.getLog().info("Fetching P2 metadata from: " + repoUrl);

        String contentJarUrl = repoUrl + "/content.jar";
        String artifactsJarUrl = repoUrl + "/artifacts.jar";

        // Download content.jar and artifacts.jar, record their checksums
        Path contentJarTmp = downloadToTemp(contentJarUrl);
        String contentJarChecksum = computeChecksum(contentJarTmp);

        Path artifactsJarTmp = downloadToTemp(artifactsJarUrl);
        String artifactsJarChecksum = computeChecksum(artifactsJarTmp);

        // Build P2Repository entry
        String localPath = "p2/" + slugify(repoUrl);
        P2Repository p2Repo = new P2Repository(
                repoUrl, contentJarUrl, contentJarChecksum,
                artifactsJarUrl, artifactsJarChecksum, localPath);

        // Parse IU dependency graph from content.jar
        Map<String, IuMetadata> iuGraph = parseContentMetadata(contentJarTmp);
        Files.deleteIfExists(contentJarTmp);

        // Parse artifact download mappings from artifacts.jar
        Map<String, ArtifactMetadata> artifactMap = parseArtifactMetadata(artifactsJarTmp);
        Files.deleteIfExists(artifactsJarTmp);

        // BFS to compute transitive closure of required IUs
        Set<String> resolved = new HashSet<>();
        Queue<String> queue = new LinkedList<>(requiredIuIds);
        while (!queue.isEmpty()) {
            String iuId = queue.poll();
            if (resolved.contains(iuId)) continue;
            resolved.add(iuId);

            IuMetadata iu = iuGraph.get(iuId);
            if (iu != null) {
                for (String dep : iu.requirements) {
                    if (!resolved.contains(dep)) {
                        queue.add(dep);
                    }
                }
            }
        }

        // Build result nodes — use checksums from artifacts.xml (no per-artifact download needed)
        List<P2DependencyNode> nodes = new ArrayList<>();
        for (String iuId : resolved) {
            ArtifactMetadata artifact = artifactMap.get(iuId);
            if (artifact == null) continue; // meta-IU with no downloadable artifact

            String downloadUrl = buildDownloadUrl(repoUrl, artifact);
            String mirrorPath = artifact.classifier + "s/" + iuId + "_" + artifact.version + artifact.extension;

            // P2 artifacts.xml already contains SHA-256 checksums — use them directly
            // to avoid downloading every artifact just for checksum computation.
            String checksum = artifact.p2Checksum;
            String checksumAlgorithm = artifact.p2ChecksumAlgorithm;

            PluginLogManager.getLog().debug("Resolved P2 artifact: " + iuId + ":" + artifact.version);
            nodes.add(new P2DependencyNode(
                    artifact.classifier,
                    iuId,
                    artifact.version,
                    downloadUrl,
                    repoUrl,
                    mirrorPath,
                    checksumAlgorithm,
                    checksum));
        }
        PluginLogManager.getLog().info(String.format(
                "Resolved %d P2 artifact(s) from %s", nodes.size(), repoUrl));
        return new P2ResolverResult(nodes, List.of(p2Repo));
    }

    // -------------------------------------------------------------------------
    // P2 content.jar / content.xml parsing
    // -------------------------------------------------------------------------

    private static Map<String, IuMetadata> parseContentMetadata(Path contentJarPath) throws Exception {
        Document contentDoc = parseP2Xml(contentJarPath, "content.xml");
        Map<String, IuMetadata> iuGraph = new HashMap<>();

        NodeList units = contentDoc.getElementsByTagName("unit");
        for (int i = 0; i < units.getLength(); i++) {
            Element unit = (Element) units.item(i);
            String id = unit.getAttribute("id");
            List<String> requirements = new ArrayList<>();

            NodeList requires = unit.getElementsByTagName("required");
            for (int j = 0; j < requires.getLength(); j++) {
                Element req = (Element) requires.item(j);
                String reqId = req.getAttribute("name");
                if (!reqId.isEmpty()) {
                    requirements.add(reqId);
                }
            }
            iuGraph.put(id, new IuMetadata(id, unit.getAttribute("version"), requirements));
        }
        return iuGraph;
    }

    // -------------------------------------------------------------------------
    // P2 artifacts.jar / artifacts.xml parsing
    // -------------------------------------------------------------------------

    private static Map<String, ArtifactMetadata> parseArtifactMetadata(Path artifactsJarPath) throws Exception {
        Document artifactsDoc = parseP2Xml(artifactsJarPath, "artifacts.xml");
        Map<String, ArtifactMetadata> artifactMap = new HashMap<>();

        NodeList artifacts = artifactsDoc.getElementsByTagName("artifact");
        for (int i = 0; i < artifacts.getLength(); i++) {
            Element artifact = (Element) artifacts.item(i);
            String id = artifact.getAttribute("id");
            String version = artifact.getAttribute("version");
            String classifier = artifact.getAttribute("classifier");

            // Extract checksum from properties — prefer SHA-256, fall back to MD5
            String checksum = "";
            String checksumAlgorithm = SHA256;
            NodeList props = artifact.getElementsByTagName("property");
            for (int j = 0; j < props.getLength(); j++) {
                Element prop = (Element) props.item(j);
                String name = prop.getAttribute("name");
                if ("download.checksum.sha-256".equals(name)) {
                    checksum = prop.getAttribute("value");
                    checksumAlgorithm = SHA256;
                    break;
                } else if ("download.md5".equals(name)) {
                    checksum = prop.getAttribute("value");
                    checksumAlgorithm = "MD5";
                }
            }

            String ext = ".jar";
            artifactMap.put(id, new ArtifactMetadata(id, version, classifier, ext, checksum, checksumAlgorithm));
        }
        return artifactMap;
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static String buildDownloadUrl(String repoUrl, ArtifactMetadata artifact) {
        String folder = "osgi.bundle".equals(artifact.classifier) ? "plugins" : "features";
        return repoUrl + "/" + folder + "/" + artifact.id + "_" + artifact.version + artifact.extension;
    }

    /**
     * Extracts the named XML entry from a locally downloaded P2 JAR and parses it as DOM.
     */
    private static Document parseP2Xml(Path jarPath, String xmlEntryName) throws Exception {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            ZipEntry entry = jar.getEntry(xmlEntryName);
            if (entry == null) {
                throw new IOException("Entry " + xmlEntryName + " not found in " + jarPath);
            }
            try (InputStream is = jar.getInputStream(entry)) {
                return parseXml(is);
            }
        }
    }

    /**
     * Generates a filesystem-safe slug from a P2 repository URL for use as a
     * directory name under hermeto-output/deps/p2/.
     * e.g. "https://download.eclipse.org/jdtls/milestones/1.51.0/repository" -> "jdtls-milestones-1.51.0"
     */
    private static String slugify(String repoUrl) {
        String path = URI.create(repoUrl).getPath();
        // strip leading/trailing slashes and trailing "repository" segment
        String[] segments = path.replaceAll("^/|/$", "").split("/");
        // take up to last 3 meaningful segments, skip generic names like "repository"
        List<String> parts = new ArrayList<>();
        for (int i = segments.length - 1; i >= 0 && parts.size() < 3; i--) {
            String seg = segments[i];
            if (!seg.isEmpty() && !"repository".equals(seg) && !"updates".equals(seg)) {
                parts.add(0, seg);
            }
        }
        return String.join("-", parts).replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    /** Computes SHA-256 checksum of a local file. */
    private static String computeChecksum(Path file) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance(SHA256);
            byte[] hash = digest.digest(Files.readAllBytes(file));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static Path downloadToTemp(String url) throws IOException {
        Path tmp = Files.createTempFile("maven-lockfile-p2-", ".tmp");
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            conn.disconnect();
        }
        return tmp;
    }

    private static Document parseXml(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(file);
    }

    private static Document parseXml(InputStream is) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(is);
    }

    // -------------------------------------------------------------------------
    // Internal data structures
    // -------------------------------------------------------------------------

    private static class IuMetadata {
        final String id;
        final String version;
        final List<String> requirements;

        IuMetadata(String id, String version, List<String> requirements) {
            this.id = id;
            this.version = version;
            this.requirements = requirements;
        }
    }

    private static class ArtifactMetadata {
        final String id;
        final String version;
        final String classifier;
        final String extension;
        final String p2Checksum;
        final String p2ChecksumAlgorithm;

        ArtifactMetadata(String id, String version, String classifier, String extension,
                String p2Checksum, String p2ChecksumAlgorithm) {
            this.id = id;
            this.version = version;
            this.classifier = classifier;
            this.extension = extension;
            this.p2Checksum = p2Checksum;
            this.p2ChecksumAlgorithm = p2ChecksumAlgorithm;
        }
    }
}
