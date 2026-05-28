/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.jdeps.runner;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;

import edu.illinois.starts.util.Logger;

/**
 * Lance les tests via MavenInvoker :
 *   - invokeSurefire() -> mvn test          (tests unitaires TU)
 *   - invokeFailsafe() -> mvn failsafe:*    (tests d'integration TI)
 *
 * Avant les TI, effectue systematiquement :
 *   1. Build de config-dev (mvn install -DskipTests) pour garantir la jar .m2 a jour
 *   2. Execution du script d'initialisation BDD locale (init_bdd_localhost.sh)
 *
 * Transmet au sous-process :
 *   - MAVEN_OPTS (truststore SSL, memoire JVM)
 *
 * La sortie du sous-process est capturee dans RunReport.
 */
public class MavenTestRunner {

    private final MavenProject project;
    private final RunReport    report;
    private final Logger       logger;
    private final String       configDevPomPath;
    private final String       initDbScriptPath;

    public MavenTestRunner(MavenProject project,
                           RunReport    report,
                           String       configDevPomPath,
                           String       initDbScriptPath) {
        this.project          = project;
        this.report           = report;
        this.logger           = Logger.getGlobal();
        this.configDevPomPath = configDevPomPath;
        this.initDbScriptPath = initDbScriptPath;
    }

    // -------------------------------------------------------------------------
    // Surefire - tests unitaires
    // -------------------------------------------------------------------------

    /**
     * Lance les tests unitaires via Surefire.
     * Pas de limite sur le nombre de classes.
     *
     * @param testClasses noms simples des classes (ex: TestFiltreRequete)
     * @return true si tous les tests passent
     */
    public boolean invokeSurefire(List<String> testClasses) throws MojoExecutionException {
        report.log("  Classes : " + String.join(",", testClasses));

        Properties props = buildCommonProperties();
        props.setProperty("test", String.join(",", testClasses));

        return invokeMaven(
                new File(project.getFile().getAbsolutePath()),
                List.of("test"),
                props);
    }

    // -------------------------------------------------------------------------
    // Failsafe - tests d'integration
    // -------------------------------------------------------------------------

    /**
     * Prepare et lance les tests d'integration via Failsafe.
     *
     * Etapes prealables :
     *   1. Build de config-dev pour garantir la jar a jour dans le .m2
     *   2. Initialisation de la BDD locale via le script shell
     *
     * @param testClasses noms simples des classes (ex: TestITFiltreDAO)
     * @return true si tous les tests passent
     */
    public boolean invokeFailsafe(List<String> testClasses) throws MojoExecutionException {
        // -- Pre-requis -------------------------------------------------------
        buildConfigDev();
        prepareDatabase();

        // -- Lancement Failsafe -----------------------------------------------
        report.log("  Classes : " + String.join(",", testClasses));

        Properties props = buildCommonProperties();
        props.setProperty("it.test", String.join(",", testClasses));
        props.setProperty("skipITs", "false");

        return invokeMaven(
                new File(project.getFile().getAbsolutePath()),
                List.of("failsafe:integration-test", "failsafe:verify"),
                props);
    }

    // -------------------------------------------------------------------------
    // Build config-dev (toujours, pour garantir la jar a jour)
    // -------------------------------------------------------------------------

    private void buildConfigDev() throws MojoExecutionException {
        report.section("Pre-requis : build de config-dev");

        File configDevPom = resolveFile(configDevPomPath);
        if (!configDevPom.exists()) {
            report.log("  [WARN] config-dev introuvable : " + configDevPom.getAbsolutePath());
            report.log("         Les TI risquent d'echouer si la jar est absente du .m2.");
            return;
        }

        report.log("  -> " + configDevPom.getAbsolutePath());

        Properties props = new Properties();
        props.setProperty("skipTests",       "true");
        props.setProperty("maven.test.skip", "true");

        boolean ok = invokeMaven(configDevPom, List.of("install"), props);
        if (ok) {
            report.log("  [OK] config-dev installe dans le .m2");
        } else {
            report.log("  [WARN] Echec build config-dev - les TI continuent malgre tout");
        }
    }

    // -------------------------------------------------------------------------
    // Initialisation BDD locale via script shell
    // -------------------------------------------------------------------------

    private void prepareDatabase() {
        report.section("Pre-requis : initialisation BDD locale");

        File script = resolveFile(initDbScriptPath);
        if (!script.exists()) {
            report.log("  [WARN] Script BDD introuvable : " + script.getAbsolutePath());
            report.log("         Les TI tournent sans reinitialisation de la BDD.");
            return;
        }

        report.log("  -> " + script.getAbsolutePath());

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", script.getAbsolutePath());
            pb.directory(script.getParentFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Capturer toute la sortie du script dans le rapport
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    report.log("  [BDD] " + line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                report.log("  [OK] BDD locale initialisee");
            } else {
                report.log("  [WARN] Echec initialisation BDD (code : " + exitCode + ")");
                report.log("         Les TI tournent malgre tout.");
            }
        } catch (IOException e) {
            report.log("  [WARN] Erreur execution script BDD : " + e.getMessage());
        } catch (InterruptedException e) {
            report.log("  [WARN] Script BDD interrompu : " + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Invocation Maven commune
    // -------------------------------------------------------------------------

    private boolean invokeMaven(File pom, List<String> goals, Properties props)
            throws MojoExecutionException {

        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(pom);
        request.setGoals(goals);
        request.setProperties(props);
        request.setBatchMode(true);

        // Transmettre MAVEN_OPTS (truststore SSL, memoire JVM...)
        String mavenOpts = System.getenv("MAVEN_OPTS");
        if (mavenOpts != null && !mavenOpts.isEmpty()) {
            request.setMavenOpts(mavenOpts);
        }

        // Capturer stdout + stderr du sous-process dans le rapport
        request.setOutputHandler(line -> report.log("  [MVN] " + line));
        request.setErrorHandler(line  -> report.log("  [ERR] " + line));

        Invoker invoker = new DefaultInvoker();
        invoker.setWorkingDirectory(pom.getParentFile());

        try {
            InvocationResult result = invoker.execute(request);
            if (result.getExitCode() != 0) {
                logger.log(Level.WARNING,
                        "Maven a retourne le code : " + result.getExitCode()
                        + " pour : " + goals);
                report.log("  Rapports : " + project.getBuild().getDirectory()
                        + "/surefire-reports/");
                return false;
            }
            return true;
        } catch (MavenInvocationException e) {
            throw new MojoExecutionException(
                    "Erreur invocation Maven " + goals + " : " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Properties communes aux invocations de tests
    // -------------------------------------------------------------------------

    private Properties buildCommonProperties() {
        Properties props = new Properties();
        props.setProperty("failIfNoTests",                           "false");
        props.setProperty("maven.compiler.useIncrementalCompilation","false");
        props.setProperty("skipTests",                               "false");
        props.setProperty("maven.test.skip",                         "false");
        return props;
    }

    // -------------------------------------------------------------------------
    // Utilitaire : resolution de chemin relatif/absolu
    // -------------------------------------------------------------------------

    private File resolveFile(String path) {
        File f = new File(path);
        return f.isAbsolute() ? f : new File(project.getBasedir(), path);
    }
}
