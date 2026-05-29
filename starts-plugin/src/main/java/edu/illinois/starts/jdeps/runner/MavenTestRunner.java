/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.jdeps.runner;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
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
 * En cas de succes, la sortie Maven n'est pas loggee.
 * En cas d'echec, seules les lignes decrivant des tests en erreur sont conservees.
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
     *
     * @param testClasses noms simples des classes (ex: TestFiltreRequete)
     * @return true si tous les tests passent
     */
    public boolean invokeSurefire(List<String> testClasses) throws MojoExecutionException {
        report.log("  Classes : " + String.join(",", testClasses));

        Properties props = buildCommonProperties();

        // Ecrire la liste dans un fichier pour eviter la limite Windows (8191 chars)
        File includesFile = writeTestsToFile(testClasses, "surefire-includes");
        props.setProperty("includesFile", includesFile.getAbsolutePath());

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
     * @param testClasses noms simples des classes (ex: TestITFiltreDAO)
     * @return true si tous les tests passent
     */
    public boolean invokeFailsafe(List<String> testClasses) throws MojoExecutionException {
        buildConfigDev();
        if (!prepareDatabase()) {
            report.log("  [ABORT] Les TI ne sont pas lancees : BDD non initialisee.");
            return false;
        }

        report.log("  Classes : " + String.join(",", testClasses));

        Properties props = buildCommonProperties();
        props.setProperty("skipITs", "false");

        // Ecrire la liste dans un fichier pour eviter la limite Windows (8191 chars)
        File includesFile = writeTestsToFile(testClasses, "failsafe-includes");
        props.setProperty("failsafe.includesFile", includesFile.getAbsolutePath());

        return invokeMaven(
                new File(project.getFile().getAbsolutePath()),
                List.of("failsafe:integration-test", "failsafe:verify"),
                props);
    }

    // -------------------------------------------------------------------------
    // Build config-dev
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
        report.log(ok ? "  [OK] config-dev installe dans le .m2"
                           : "  [WARN] Echec build config-dev - les TI continuent malgre tout");
    }

    // -------------------------------------------------------------------------
    // Initialisation BDD locale
    // -------------------------------------------------------------------------

    private boolean prepareDatabase() {
        report.section("Pre-requis : initialisation BDD locale");

        File script = resolveFile(initDbScriptPath);
        if (!script.exists()) {
            report.log("  [ERREUR] Script BDD introuvable : " + script.getAbsolutePath());
            return false;
        }

        // Bufferiser la sortie du script : on ne l'affiche qu'en cas d'echec
        List<String> bddLines = new ArrayList<>();
        bddLines.add("  -> " + script.getAbsolutePath());

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", script.getAbsolutePath());
            pb.directory(script.getParentFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    bddLines.add("  [BDD] " + line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                report.log("  [OK] BDD locale initialisee");
                return true;
            } else {
                // Echec : on vide le buffer dans le rapport
                bddLines.forEach(report::log);
                report.log("  [ERREUR] Echec initialisation BDD (code : " + exitCode + ")");
                return false;
            }
        } catch (IOException e) {
            bddLines.forEach(report::log);
            report.log("  [ERREUR] Erreur execution script BDD : " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            bddLines.forEach(report::log);
            report.log("  [ERREUR] Script BDD interrompu : " + e.getMessage());
            Thread.currentThread().interrupt();
            return false;
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

        String mavenOpts = System.getenv("MAVEN_OPTS");
        if (mavenOpts != null && !mavenOpts.isEmpty()) {
            request.setMavenOpts(mavenOpts);
        }

        // Capturer toute la sortie en memoire.
        // En cas de succes : rien ne s'affiche.
        // En cas d'echec  : on filtre et on ne garde que les lignes utiles.
        List<String> outputLines = new ArrayList<>();
        request.setOutputHandler(outputLines::add);
        request.setErrorHandler(outputLines::add);

        Invoker invoker = new DefaultInvoker();
        invoker.setWorkingDirectory(pom.getParentFile());

        try {
            InvocationResult result = invoker.execute(request);
            if (result.getExitCode() != 0) {
                logger.log(Level.WARNING,
                           "Maven a retourne le code : " + result.getExitCode()
                                   + " pour : " + goals);
                report.log("  Sortie Maven (" + outputLines.size() + " lignes capturees) :");
                outputLines.stream()
                        .filter(RunReport::isRelevantLine)
                        .forEach(line -> report.log("    " + line));
                report.log("  Rapports complets : "
                                   + project.getBuild().getDirectory() + "/surefire-reports/");
                return false;
            }
            return true;
        } catch (MavenInvocationException e) {
            throw new MojoExecutionException(
                    "Erreur invocation Maven " + goals + " : " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Properties communes
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
    // Utilitaire
    // -------------------------------------------------------------------------

    private File resolveFile(String path) {
        File f = new File(path);
        return f.isAbsolute() ? f : new File(project.getBasedir(), path);
    }

    /**
     * Ecrit les noms de classes de test dans un fichier temporaire,
     * un pattern par ligne (format attendu par Surefire includesFile).
     * Evite la limite de longueur de ligne de commande sur Windows.
     *
     * @param testClasses liste des noms simples de classes de test
     * @param prefix      prefixe du fichier temporaire
     * @return le fichier cree dans le repertoire target/
     * @throws MojoExecutionException si l'ecriture echoue
     */
    private File writeTestsToFile(List<String> testClasses, String prefix)
            throws MojoExecutionException {
        try {
            File targetDir = new File(project.getBuild().getDirectory());
            targetDir.mkdirs();
            File file = new File(targetDir, prefix + ".txt");
            // Surefire attend des patterns avec **/ prefix et .java suffix
            List<String> patterns = testClasses.stream()
                    .map(c -> "**/" + c + ".java")
                    .collect(java.util.stream.Collectors.toList());
            java.nio.file.Files.write(file.toPath(), patterns,
                                      java.nio.charset.StandardCharsets.UTF_8);
            report.log("  [includes] " + file.getAbsolutePath()
                               + " (" + testClasses.size() + " tests)");
            return file;
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Impossible d'ecrire le fichier includes : " + e.getMessage(), e);
        }
    }
}