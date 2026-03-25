package io.github.chains_project.maven_lockfile.resolvers;

import io.github.chains_project.maven_lockfile.reporting.PluginLogManager;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Discovers Quarkus deployment artifacts that {@code quarkus-maven-plugin:generate-code}
 * dynamically loads at runtime via its bootstrap mechanism.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Detect Quarkus project (presence of {@code quarkus-maven-plugin} in build plugins).
 *   <li>Infer the Quarkus platform version from the resolved {@code quarkus-core} artifact.
 *   <li>Add {@code quarkus-bom-quarkus-platform-properties} (type=properties) — required by the
 *       Quarkus bootstrap to locate the platform descriptor.
 *   <li>Scan every resolved project dependency JAR for
 *       {@code META-INF/quarkus-extension.properties}. If present, read the
 *       {@code deployment-artifact} property (format {@code groupId:artifactId:version}) and emit
 *       a corresponding {@link Dependency} so maven-lockfile records it.
 *   <li>For each discovered deployment artifact, read its POM from the local Maven repository to
 *       find optional ("conditional") {@code *-deployment} dependencies. The Quarkus bootstrap
 *       resolves these to check whether their runtime counterpart is present in the project —
 *       even when it is not, the runtime JAR must be available offline so the check can succeed.
 *       Both the optional deployment artifact and its runtime counterpart are added to the result.
 * </ol>
 *
 * <p>The returned list is injected as user-declared plugin dependencies for
 * {@code quarkus-maven-plugin} in {@code LockFileFacade.getAllPlugins()}, so the full transitive
 * closure of every deployment artifact is resolved and written to the lockfile — without any
 * manual changes to the project's {@code pom.xml}.
 */
public class QuarkusDeploymentResolver {

    private static final String QUARKUS_PLUGIN_ARTIFACT_ID = "quarkus-maven-plugin";
    private static final String QUARKUS_EXTENSION_PROPERTIES = "META-INF/quarkus-extension.properties";
    private static final String DEPLOYMENT_ARTIFACT_KEY = "deployment-artifact";
    private static final String PLATFORM_PROPERTIES_GROUP = "io.quarkus";
    private static final String PLATFORM_PROPERTIES_ARTIFACT = "quarkus-bom-quarkus-platform-properties";
    private static final String DEPLOYMENT_SUFFIX = "-deployment";

    private QuarkusDeploymentResolver() {}

    /**
     * Returns {@code true} if the project uses {@code quarkus-maven-plugin}.
     */
    public static boolean isQuarkusProject(MavenProject project) {
        return project.getBuildPlugins().stream()
                .anyMatch(p -> QUARKUS_PLUGIN_ARTIFACT_ID.equals(p.getArtifactId()));
    }

    /**
     * Discovers deployment artifact dependencies that the Quarkus bootstrap will load dynamically
     * during {@code generate-code}. These are not declared in the project's {@code pom.xml} but
     * are required for a hermetic offline build.
     *
     * <p>In addition to primary deployment artifacts (from {@code META-INF/quarkus-extension.properties}),
     * also discovers optional "conditional" deployment artifacts declared in deployment POMs.
     * The Quarkus bootstrap resolves these at startup to determine whether the corresponding
     * runtime extension is active — even when it is not, the runtime JAR must be accessible
     * offline for the resolution check to succeed.
     *
     * @param project the Maven project
     * @param session the Maven session (used to locate the local repository for POM scanning)
     * @return list of deployment artifact {@link Dependency} objects to inject as plugin deps
     */
    public static List<Dependency> discoverDeploymentDependencies(MavenProject project, MavenSession session) {
        List<Dependency> deploymentDeps = new ArrayList<>();
        Set<String> seen = new HashSet<>(); // groupId:artifactId dedup key

        // Always add platform-properties — Quarkus bootstrap needs this to identify the platform.
        String quarkusVersion = findQuarkusVersion(project);
        if (quarkusVersion != null) {
            Dependency platformProps = new Dependency();
            platformProps.setGroupId(PLATFORM_PROPERTIES_GROUP);
            platformProps.setArtifactId(PLATFORM_PROPERTIES_ARTIFACT);
            platformProps.setVersion(quarkusVersion);
            platformProps.setType("properties");
            addIfAbsent(deploymentDeps, seen, platformProps);
            PluginLogManager.getLog().info(
                    "Quarkus: adding platform-properties " + PLATFORM_PROPERTIES_ARTIFACT + ":" + quarkusVersion);
        } else {
            PluginLogManager.getLog().warn(
                    "Quarkus: could not determine Quarkus version — skipping platform-properties");
        }

        // Phase 1: scan each resolved JAR for META-INF/quarkus-extension.properties
        List<Dependency> primaryDeploymentDeps = new ArrayList<>();
        for (Artifact artifact : project.getArtifacts()) {
            File artifactFile = artifact.getFile();
            if (artifactFile == null || !artifactFile.exists()) continue;
            if (!"jar".equals(artifact.getType())) continue;

            try {
                String deploymentGav = readDeploymentArtifact(artifactFile);
                if (deploymentGav == null) continue;

                String[] parts = deploymentGav.split(":");
                if (parts.length < 3) {
                    PluginLogManager.getLog().warn(
                            "Quarkus: malformed deployment-artifact value in " + artifact + ": " + deploymentGav);
                    continue;
                }

                Dependency dep = new Dependency();
                dep.setGroupId(parts[0]);
                dep.setArtifactId(parts[1]);
                dep.setVersion(parts[2]);
                if (addIfAbsent(deploymentDeps, seen, dep)) {
                    primaryDeploymentDeps.add(dep);
                    PluginLogManager.getLog().debug(
                            "Quarkus: discovered deployment artifact " + deploymentGav
                                    + " (from " + artifact.getArtifactId() + ")");
                }
            } catch (IOException e) {
                PluginLogManager.getLog().debug(
                        "Quarkus: could not read " + artifactFile.getName() + ": " + e.getMessage());
            }
        }

        PluginLogManager.getLog().info(String.format(
                "Quarkus: discovered %d primary deployment artifact(s)", primaryDeploymentDeps.size()));

        // Phase 2: for each primary deployment artifact, read its POM to find:
        //   a) optional "conditional" *-deployment deps + their runtime counterparts
        //   b) version-less io.quarkus deps that would be misresolved when plugin version != BOM version
        // Also apply the same scan one level deeper for any newly discovered deployment deps.
        String localRepoBase = session.getLocalRepository().getBasedir();
        int extraCount = 0;
        List<Dependency> toScan = new ArrayList<>(primaryDeploymentDeps);
        // Track which deployment artifact POMs we've already scanned to avoid redundant work.
        Set<String> scanned = new HashSet<>();
        while (!toScan.isEmpty()) {
            List<Dependency> nextScan = new ArrayList<>();
            for (Dependency deploymentDep : toScan) {
                String scanKey = deploymentDep.getGroupId() + ":" + deploymentDep.getArtifactId()
                        + ":" + deploymentDep.getVersion();
                if (!scanned.add(scanKey)) continue;

                List<Dependency> pomDeps = discoverDeploymentPomDeps(deploymentDep, localRepoBase, quarkusVersion);
                for (Dependency pd : pomDeps) {
                    if (addIfAbsent(deploymentDeps, seen, pd)) {
                        extraCount++;
                        PluginLogManager.getLog().debug(
                                "Quarkus: adding POM dep " + pd.getArtifactId()
                                        + ":" + pd.getVersion()
                                        + " (from " + deploymentDep.getArtifactId() + ")");
                        // Queue newly discovered deployment artifacts for POM scanning too.
                        if (pd.getArtifactId().endsWith(DEPLOYMENT_SUFFIX)) {
                            nextScan.add(pd);
                        }
                    }
                }
            }
            toScan = nextScan;
        }

        PluginLogManager.getLog().info(String.format(
                "Quarkus: added %d extra artifact(s) from deployment POM scanning",
                extraCount));
        return deploymentDeps;
    }

    /**
     * Reads the POM of a deployment artifact from the local Maven repository and returns
     * additional dependencies that the Quarkus bootstrap needs but Maven's standard resolver
     * would miss. Two categories are captured:
     *
     * <ol>
     *   <li><b>Optional (conditional) {@code *-deployment} deps</b> — the bootstrap processes
     *       these to check whether the corresponding runtime extension is present, even when it
     *       is not. Both the deployment artifact and its runtime counterpart are included.</li>
     *   <li><b>Version-less {@code io.quarkus} deps</b> — deployment POMs often omit the version
     *       on intra-platform deps, relying on the Quarkus BOM to supply it. When the plugin
     *       version differs from the platform BOM version (e.g. plugin=3.27.0, BOM=3.27.2),
     *       Maven's resolver picks up the plugin's older BOM and cannot resolve newer artifacts.
     *       We assign the platform version explicitly so hermeto downloads the correct JARs.</li>
     * </ol>
     */
    private static List<Dependency> discoverDeploymentPomDeps(
            Dependency deploymentDep, String localRepoBase, String platformVersion) {
        List<Dependency> result = new ArrayList<>();
        File pomFile = localRepoPomFile(localRepoBase, deploymentDep);
        if (pomFile == null || !pomFile.exists()) {
            return result;
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(pomFile);

            NodeList depNodes = doc.getElementsByTagName("dependency");
            for (int i = 0; i < depNodes.getLength(); i++) {
                Element depElem = (Element) depNodes.item(i);
                String groupId = childText(depElem, "groupId");
                String artifactId = childText(depElem, "artifactId");
                if (groupId == null || artifactId == null) continue;

                String optional = childText(depElem, "optional");
                boolean isOptional = "true".equals(optional);

                String rawVersion = childText(depElem, "version");
                boolean isVersionless = rawVersion == null || rawVersion.startsWith("${");

                // Resolve version: prefer explicit; fall back to platform version for io.quarkus*
                // artifacts (BOM-managed), or to parent dep version as last resort.
                // Use startsWith to cover sub-groups like io.quarkus.resteasy.reactive.
                String version = rawVersion;
                if (isVersionless) {
                    if (groupId != null && groupId.startsWith("io.quarkus")) {
                        version = platformVersion;
                    } else {
                        // Non-Quarkus versionless dep — skip, Maven's resolver handles these.
                        continue;
                    }
                }
                if (version == null || version.startsWith("${")) {
                    version = deploymentDep.getVersion();
                }

                // Category 1: optional *-deployment (conditional extension)
                if (isOptional && artifactId.endsWith(DEPLOYMENT_SUFFIX)) {
                    Dependency optDeploymentDep = new Dependency();
                    optDeploymentDep.setGroupId(groupId);
                    optDeploymentDep.setArtifactId(artifactId);
                    optDeploymentDep.setVersion(version);
                    result.add(optDeploymentDep);

                    // Add the corresponding runtime artifact so the bootstrap can inspect it.
                    String runtimeArtifactId = artifactId.substring(
                            0, artifactId.length() - DEPLOYMENT_SUFFIX.length());
                    Dependency runtimeDep = new Dependency();
                    runtimeDep.setGroupId(groupId);
                    runtimeDep.setArtifactId(runtimeArtifactId);
                    runtimeDep.setVersion(version);
                    result.add(runtimeDep);
                    continue;
                }

                // Category 2: version-less io.quarkus* dep (BOM-managed, may be missing when
                // plugin version != platform BOM version). Inject explicitly with the platform version.
                // groupId prefix "io.quarkus" covers io.quarkus, io.quarkus.resteasy.reactive, etc.
                // This captures *-spi, *-processor, *-dev, *-deployment-spi and similar build-time
                // artifacts that appear in deployment POMs without explicit versions.
                //
                // Note: injecting these at the platform version (e.g. 3.27.2) may conflict with the
                // plugin's own pinned versions (e.g. 3.27.0) for shared artifacts like
                // quarkus-bootstrap-maven-resolver. LockFileFacade handles this by running a
                // two-phase resolution for quarkus-maven-plugin (native + extended) and unioning
                // the results, so both versions are captured in the lockfile.
                if (isVersionless && groupId != null && groupId.startsWith("io.quarkus") && !isOptional) {
                    Dependency explicitDep = new Dependency();
                    explicitDep.setGroupId(groupId);
                    explicitDep.setArtifactId(artifactId);
                    explicitDep.setVersion(version);
                    String type = childText(depElem, "type");
                    if (type != null) explicitDep.setType(type);
                    result.add(explicitDep);
                }
            }
        } catch (Exception e) {
            PluginLogManager.getLog().debug(
                    "Quarkus: could not parse POM for " + deploymentDep.getArtifactId() + ": " + e.getMessage());
        }
        return result;
    }

    /**
     * Computes the path to a POM file in the local Maven repository for the given dependency.
     * e.g. {@code ~/.m2/repository/io/quarkus/quarkus-rest-deployment/3.27.2/quarkus-rest-deployment-3.27.2.pom}
     */
    private static File localRepoPomFile(String localRepoBase, Dependency dep) {
        String groupPath = dep.getGroupId().replace('.', File.separatorChar);
        String fileName = dep.getArtifactId() + "-" + dep.getVersion() + ".pom";
        return new File(localRepoBase,
                groupPath + File.separator + dep.getArtifactId()
                        + File.separator + dep.getVersion() + File.separator + fileName);
    }

    /**
     * Reads {@code META-INF/quarkus-extension.properties} from a JAR and returns the value of
     * the {@code deployment-artifact} property, or {@code null} if not present.
     */
    private static String readDeploymentArtifact(File jarFile) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            ZipEntry entry = jar.getEntry(QUARKUS_EXTENSION_PROPERTIES);
            if (entry == null) return null;

            Properties props = new Properties();
            try (InputStream is = jar.getInputStream(entry)) {
                props.load(is);
            }
            return props.getProperty(DEPLOYMENT_ARTIFACT_KEY);
        }
    }

    /**
     * Infers the Quarkus platform version by looking for {@code io.quarkus:quarkus-core} in the
     * resolved project artifacts.
     */
    private static String findQuarkusVersion(MavenProject project) {
        return project.getArtifacts().stream()
                .filter(a -> "io.quarkus".equals(a.getGroupId())
                        && "quarkus-core".equals(a.getArtifactId()))
                .map(Artifact::getVersion)
                .findFirst()
                .orElse(null);
    }

    /** Returns the text content of the first direct child element with the given tag name. */
    private static String childText(Element parent, String tagName) {
        NodeList nl = parent.getElementsByTagName(tagName);
        if (nl.getLength() == 0) return null;
        // Only use direct children to avoid descending into nested elements
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i).getParentNode() == parent) {
                return nl.item(i).getTextContent().trim();
            }
        }
        return null;
    }

    /**
     * Adds {@code dep} to {@code list} if its {@code groupId:artifactId} key is not in {@code seen}.
     * Returns {@code true} if the dep was added.
     */
    private static boolean addIfAbsent(List<Dependency> list, Set<String> seen, Dependency dep) {
        String key = dep.getGroupId() + ":" + dep.getArtifactId();
        if (seen.add(key)) {
            list.add(dep);
            return true;
        }
        return false;
    }
}
