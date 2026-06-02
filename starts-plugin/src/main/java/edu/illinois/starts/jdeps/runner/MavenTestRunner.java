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
    private final int          surefireForkCount;
    private final int          failsafeForkCount;

    /** Flux console direct, non intercepte par le logging Maven. */
    private static final java.io.PrintStream CONSOLE =
            new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out));

    public MavenTestRunner(MavenProject project,
                           RunReport    report,
                           String       configDevPomPath,
                           String       initDbScriptPath,
                           int          surefireForkCount,
                           int          failsafeForkCount) {
        this.project           = project;
        this.report            = report;
        this.logger            = Logger.getGlobal();
        this.configDevPomPath  = configDevPomPath;
        this.initDbScriptPath  = initDbScriptPath;
        this.surefireForkCount = surefireForkCount;
        this.failsafeForkCount = failsafeForkCount;
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
        // "surefire:test" (goal direct) et NON "test" (phase) : la phase declenche
        // tout le cycle jusqu'a test-compile, donc recompile. Le goal direct lance
        // uniquement Surefire sur les .class deja compiles par le shell.
        return runTestsInChunks(testClasses, "test", "surefire:test", false);
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
        if (!prepareDatabase()) {
            report.log("  [ABORT] Les TI ne sont pas lancees : BDD non initialisee.");
            return false;
        }
        return runTestsInChunks(testClasses, "it.test", "failsafe:integration-test,failsafe:verify", true);
    }

    /**
     * Lance les tests en plusieurs lots pour eviter la limite Windows de 8191 chars
     * sur -Dtest=...
     *
     * @param testClasses  liste complete des tests
     * @param propertyName "test" (Surefire) ou "it.test" (Failsafe)
     * @param goals        goals Maven separes par virgule
     * @param isFailsafe   true pour ajouter skipITs=false
     * @return true si tous les lots passent
     */
    private boolean runTestsInChunks(List<String> testClasses, String propertyName,
                                     String goals, boolean isFailsafe)
            throws MojoExecutionException {

        // Determiner la taille de chunk : moyenne ~30 chars/nom + virgules = ~32
        // Limite Windows 8191, on garde une marge pour les autres args -> ~6000 chars utiles
        final int maxCharsPerChunk = 6000;
        List<List<String>> chunks = splitIntoChunks(testClasses, maxCharsPerChunk);

        report.log("  Classes : " + testClasses.size() + " test(s) en " + chunks.size() + " lot(s)");

        // Vider entierement le dossier des rapports avant de lancer les lots
        // (XML + .txt + failsafe-summary.xml + tout autre fichier residuel),
        // pour que FailedTestsTracker ne remonte que les echecs de ce run.
        String reportsDirName = isFailsafe ? "failsafe-reports" : "surefire-reports";
        File reportsDir = new File(project.getBuild().getDirectory(), reportsDirName);
        if (reportsDir.exists()) {
            int deleted = deleteDirContents(reportsDir);
            if (deleted > 0) {
                report.log("  [reports] " + deleted + " ancien(s) fichier(s) supprime(s) dans "
                                   + reportsDirName + "/");
            }
        }

        List<String> goalsList = List.of(goals.split(","));
        boolean allOk = true;

        for (int i = 0; i < chunks.size(); i++) {
            List<String> chunk = chunks.get(i);
            if (chunks.size() > 1) {
                report.log("  -- Lot " + (i + 1) + "/" + chunks.size()
                                   + " (" + chunk.size() + " tests) --");
            }

            Properties props = buildCommonProperties();
            props.setProperty(propertyName, String.join(",", chunk));
            if (isFailsafe) {
                props.setProperty("skipITs", "false");
                // TI : 1 JVM neuve par classe (isolation BDD), plusieurs en parallele
                props.setProperty("forkCount", String.valueOf(failsafeForkCount));
                props.setProperty("reuseForks", "false");
            } else {
                // TU : JVM reutilisees entre classes, plusieurs en parallele
                props.setProperty("forkCount", String.valueOf(surefireForkCount));
                props.setProperty("reuseForks", "true");
            }

            boolean ok = invokeMaven(
                    new File(project.getFile().getAbsolutePath()),
                    goalsList,
                    props, chunk.size());
            if (!ok) {
                allOk = false;
                // On continue les autres lots meme si un lot echoue
            }
        }
        return allOk;
    }

    /**
     * Decoupe une liste de noms de tests en lots dont la concatenation
     * (separateur virgule) ne depasse pas maxChars.
     */
    private static List<List<String>> splitIntoChunks(List<String> tests, int maxChars) {
        List<List<String>> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentSize = 0;
        for (String t : tests) {
            int addSize = t.length() + 1; // +1 pour la virgule
            if (currentSize + addSize > maxChars && !current.isEmpty()) {
                chunks.add(current);
                current = new ArrayList<>();
                currentSize = 0;
            }
            current.add(t);
            currentSize += addSize;
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    // -------------------------------------------------------------------------
    // Build config-dev
    // -------------------------------------------------------------------------

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

    private boolean invokeMaven(File pom, List<String> goals, Properties props, int total)
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

        // Suivi de progression via parsing des lignes Surefire/Failsafe
        final ProgressWatcher watcher = total > 0 ? new ProgressWatcher(total, CONSOLE) : null;
        if (watcher != null) {
            watcher.start();
        }

        // Capturer toute la sortie en memoire + alimenter le watcher.
        // En cas de succes : seule la progression s'affiche.
        // En cas d'echec  : les lignes utiles sont filtrees et loggees.
        List<String> outputLines = new ArrayList<>();
        request.setOutputHandler(line -> {
            outputLines.add(line);
            if (watcher != null) {
                watcher.onMavenLine(line);
            }
        });
        request.setErrorHandler(outputLines::add);

        Invoker invoker = new DefaultInvoker();
        invoker.setWorkingDirectory(pom.getParentFile());

        long startNanos = System.nanoTime();
        try {
            InvocationResult result = invoker.execute(request);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            if (watcher != null) {
                watcher.stop();
            }
            report.log("  [duree] " + formatDuration(elapsedMs));
            if (result.getExitCode() != 0) {
                logger.log(Level.WARNING,
                           "Maven a retourne le code : " + result.getExitCode()
                                   + " pour : " + goals);
                long matched = outputLines.stream()
                        .filter(line -> RunReport.isFailureLine(line) || RunReport.isTestDetailLine(line))
                        .count();
                if (matched == 0) {
                    // Filtre strict sans resultat : fallback sur les lignes [ERROR] uniquement
                    report.log("  Sortie Maven (" + outputLines.size() + " lignes, filtre de secours) :");
                    outputLines.stream()
                            .filter(line -> line != null && line.startsWith("[ERROR]"))
                            .forEach(line -> report.log("    " + line));
                } else {
                    report.log("  Tests en echec :");
                    outputLines.stream()
                            .filter(line -> RunReport.isFailureLine(line) || RunReport.isTestDetailLine(line))
                            .forEach(line -> report.log("    " + line));
                }
                report.log("  Rapports complets : "
                                   + project.getBuild().getDirectory() + "/surefire-reports/");
                return false;
            }
            return true;
        } catch (MavenInvocationException e) {
            if (watcher != null) {
                watcher.stop();
            }
            CONSOLE.println();
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
     * Extrait le nom simple de la classe depuis une ligne Surefire du type :
     * "Tests run: 1, Failures: 0, ... - in com.example.TestFoo"
     *
     * @param line ligne de sortie Surefire
     * @return nom simple de la classe (ex: TestFoo) ou la ligne entiere si non trouve
     */
    private static String extractClassName(String line) {
        int idx = line.lastIndexOf(" - in ");
        if (idx >= 0) {
            String fqn = line.substring(idx + 6).trim();
            int dot = fqn.lastIndexOf('.');
            return dot >= 0 ? fqn.substring(dot + 1) : fqn;
        }
        return line;
    }

    /**
     * Supprime recursivement le contenu d'un dossier (sans supprimer le dossier lui-meme).
     *
     * @param dir le dossier a vider
     * @return le nombre de fichiers/dossiers supprimes
     */
    private static int deleteDirContents(File dir) {
        if (!dir.exists() || !dir.isDirectory()) {
            return 0;
        }
        int deleted = 0;
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        for (File f : children) {
            if (f.isDirectory()) {
                deleted += deleteDirContents(f);
            }
            if (f.delete()) {
                deleted++;
            }
        }
        return deleted;
    }

    /**
     * Formate une duree en ms vers une chaine lisible.
     * Exemples : "342 ms", "12.5 s", "2 min 35 s".
     */
    private static String formatDuration(long ms) {
        if (ms < 1000) {
            return ms + " ms";
        }
        if (ms < 60_000) {
            return String.format("%.1f s", ms / 1000.0);
        }
        long minutes = ms / 60_000;
        long seconds = (ms % 60_000) / 1000;
        return minutes + " min " + seconds + " s";
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