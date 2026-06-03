/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.jdeps;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import edu.illinois.starts.util.Pair;
import java.util.logging.Level;

import edu.illinois.starts.constants.StartsConstants;
import edu.illinois.starts.helpers.Writer;
import edu.illinois.starts.jdeps.runner.DatabaseChecker;
import edu.illinois.starts.jdeps.runner.MavenTestRunner;
import edu.illinois.starts.jdeps.runner.FailedTestsTracker;
import edu.illinois.starts.jdeps.runner.PropertiesGuard;
import edu.illinois.starts.jdeps.runner.RunReport;
import edu.illinois.starts.jdeps.runner.TestSelector;
import edu.illinois.starts.jdeps.runner.TestSplitResult;
import edu.illinois.starts.util.Logger;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Selectionne les tests affectes par les modifications (via STARTS) et les lance
 * en separant tests unitaires (Surefire) et tests d'integration (Failsafe).
 *
 * Ce Mojo est un orchestrateur pur : il delegue a :
 *   - TestSelector     : calcul STARTS + separation TU/TI
 *   - DatabaseChecker  : detection BDD (informatif uniquement, ne bloque pas)
 *   - MavenTestRunner  : invocation Surefire et Failsafe
 *   - RunReport        : logs console + fichier horodate
 *
 * Workflow :
 *   1. Calcul des tests affectes -> selected-tests sur disque (sans maj checksums)
 *   2. Separation TU / TI
 *   3. Info BDD (informatif, ne bloque jamais les TI)
 *   4. Surefire si TU presents (pas de limite de nombre)
 *   5. Pre-requis TI : build config-dev + init BDD locale
 *   6. Failsafe si TI presents et sous le seuil maxItTests
 *   7. Checksums mis a jour seulement si tout est OK
 *
 * Usage :
 *   mvn starts:run-selected
 *   mvn starts:run-selected -DmaxItTests=200
 *   mvn starts:run-selected -DpropertiesFile=config-dev/.../framework2.properties
 *   mvn starts:run-selected -DupdateChecksums=false
 *   mvn starts:run-selected -DconfigDevPomPath=../config-dev/pom.xml
 *   mvn starts:run-selected -DinitDbScriptPath=../../scripts/init_bdd_localhost.sh
 */
@Mojo(name = "run-selected", requiresDirectInvocation = true,
        requiresDependencyResolution = ResolutionScope.TEST)
// Pas de @Execute(TEST_COMPILE) : la compilation est assuree par le script shell
// (mvn -pl <modules> -am install) AVANT l'appel. Cela evite une double
// compilation (le shell compile, puis @Execute recompilait).
public class RunSelectedMojo extends DiffMojo implements StartsConstants {

    // =========================================================================
    // Parametres
    // =========================================================================

    /**
     * Nombre maximum de tests d'integration (TI) au-dela duquel Failsafe n'est pas lance.
     * Les tests unitaires (TU) n'ont pas de limite.
     * Par defaut : 200.
     */
    @Parameter(property = "maxItTests", defaultValue = "200")
    private int maxItTests;

    /**
     * Chemin vers le fichier properties contenant JDBC_CONNECT_STRING.
     * Les lignes commentees (commencant par #) sont ignorees.
     * Utilise uniquement a titre informatif : indique dans le log
     * quelle base est configuree. Ne bloque jamais l'execution des TI.
     *
     * Exemple : config-dev/src/main/properties2/framework2.properties
     */
    @Parameter(property = "propertiesFile",
            defaultValue = "${project.basedir}/../config-dev/src/main/properties2/framework2.properties")
    private String propertiesFile;

    /**
     * Chemin vers le pom.xml de config-dev.
     * Ce module est toujours builde (mvn install -DskipTests) avant les TI
     * pour garantir que sa jar est a jour dans le .m2 local.
     */
    @Parameter(property = "configDevPomPath",
            defaultValue = "${project.basedir}/../config-dev/pom.xml")
    private String configDevPomPath;

    /**
     * Chemin vers le script shell d'initialisation de la BDD locale.
     * Execute avant chaque lancement des TI.
     * Exemple : archi/scripts/init_bdd_localhost.sh
     */
    @Parameter(property = "initDbScriptPath",
            defaultValue = "${project.basedir}/../scripts/init_bdd_localhost.sh")
    private String initDbScriptPath;

    /**
     * Repertoire dans lequel ecrire le fichier de log horodate.
     * Par defaut : ${project.basedir}/../scripts/starts/logs
     */
    @Parameter(property = "logsDir",
            defaultValue = "${project.basedir}/../scripts/starts/logs")
    private String logsDir;

    /**
     * Mise a jour des checksums STARTS apres une execution reussie.
     * Si false, les memes tests seront selectionnes au prochain lancement.
     * Par defaut : true.
     */
    @Parameter(property = "updateChecksums", defaultValue = TRUE)
    private boolean updateChecksums;

    /**
     * Nombre de JVM forkees en parallele pour les tests unitaires (Surefire).
     * Les JVM sont reutilisees entre classes (reuseForks=true).
     * Par defaut : 4. Mettre 1 pour desactiver le parallelisme.
     */
    @Parameter(property = "surefireForkCount", defaultValue = "4")
    private int surefireForkCount;

    /**
     * Nombre de JVM forkees en parallele pour les tests d'integration (Failsafe).
     * Chaque JVM est neuve par classe (reuseForks=false) pour isoler la BDD.
     * Par defaut : 1 (sequentiel). Augmenter avec prudence selon la tolerance BDD.
     */
    @Parameter(property = "failsafeForkCount", defaultValue = "1")
    private int failsafeForkCount;

    /**
     * Si true, l'init BDD n'est PAS faite dans ce Mojo (deja faite par
     * starts:prepare en amont pour le multi-module). Defaut : false.
     */
    @Parameter(property = "skipDbInit", defaultValue = "false")
    private boolean skipDbInit;

    /**
     * Si true, ne patche pas framework2.properties (deja patche par
     * starts:prepare). Defaut : false.
     */
    @Parameter(property = "skipPropertiesPatch", defaultValue = "false")
    private boolean skipPropertiesPatch;

    /**
     * Repertoire de travail partage pour les artefacts transverses
     * (failed-tests.txt agrege). Defaut : le .starts du module.
     * En multi-module, le shell passe archi/scripts/starts/work.
     */
    @Parameter(property = "workDir", defaultValue = "")
    private String workDir;

    // =========================================================================
    // Point d'entree
    // =========================================================================

    public void execute() throws MojoExecutionException {
        Logger.getGlobal().setLoggingLevel(Level.parse(loggingLevel));
        Logger logger = Logger.getGlobal();
        long start = System.currentTimeMillis();

        // -- Auto-skip : modules agregateurs (pom) ou sans tests -------------
        // En reactor complet, beaucoup de modules n'ont pas de tests, et les
        // poms agregateurs n'ont pas de plugin Surefire. On les ignore tout de
        // suite (pas de jdeps, pas de sous-process).
        if ("pom".equals(getProject().getPackaging())) {
            logger.log(Level.INFO, "[STARTS] " + getProject().getArtifactId()
                    + " : module agregateur (pom) - skip.");
            return;
        }
        File testSrcDir   = new File(getProject().getBasedir(), "src/test/java");
        File testClassDir = new File(getProject().getBuild().getTestOutputDirectory());
        boolean hasTestSources = testSrcDir.isDirectory()
                && testSrcDir.list() != null && testSrcDir.list().length > 0;
        boolean hasTestClasses = testClassDir.isDirectory()
                && testClassDir.list() != null && testClassDir.list().length > 0;
        if (!hasTestSources && !hasTestClasses) {
            logger.log(Level.INFO, "[STARTS] " + getProject().getArtifactId()
                    + " : aucun test (pas de src/test/java) - skip.");
            return;
        }

        // -- Collaborateurs --------------------------------------------------
        RunReport       report   = new RunReport(logger, logsDir, getProject().getArtifactId());
        TestSelector    selector = new TestSelector(this, report);
        DatabaseChecker dbCheck  = new DatabaseChecker(propertiesFile, getProject().getBasedir(), report);
        MavenTestRunner runner   = new MavenTestRunner(
                getProject(), report, configDevPomPath, initDbScriptPath,
                surefireForkCount, failsafeForkCount, skipDbInit);

        // -- En-tete ---------------------------------------------------------
        report.log("");
        report.separator();
        report.log("  STARTS : run-selected");
        report.separator();

        // --------------------------------------------------------------------
        // ETAPE 1 - Calcul des tests : UNION (STARTS) + (echecs precedents)
        //
        // L'union evite qu'un fix sur un test casse silencieusement un autre
        // test qui passait. Tant qu'il y a des echecs precedents, on les
        // relance, mais on continue aussi a verifier les tests affectes par
        // les changements en cours.
        // --------------------------------------------------------------------
        report.section("Etape 1 : calcul des tests affectes");

        // Afficher les classes modifiees detectees (utile pour comprendre la
        // selection). computeChangeData retourne (nonAffected, changedClasses).
        try {
            Pair<Set<String>, Set<String>> changeData = computeChangeData(false);
            Set<String> changed = changeData.getValue();
            if (changed != null && !changed.isEmpty()) {
                report.log("  Classes modifiees : " + changed.size());
                for (String url : changed) {
                    report.log("    - " + urlToFqn(url));
                }
            } else {
                report.log("  Aucune classe modifiee depuis le dernier run.");
            }
        } catch (Exception e) {
            report.warn("Impossible d'afficher les classes modifiees : " + e.getMessage());
        }

        Set<String> affectedTests = new LinkedHashSet<>(selector.computeAffectedTests());
        int startsCount = affectedTests.size();

        // Mode RETRY : le failed-tests.txt est LOCAL au module (pas le workDir
        // partage, qui melangeait les modules). S'il existe et n'est pas vide,
        // on ne rejoue QUE ces echecs et on IGNORE les changements courants,
        // jusqu'a ce que tout soit vert. Ensuite on revient au mode STARTS normal.
        FailedTestsTracker localTracker = new FailedTestsTracker(getProject().getBasedir());
        List<String> previousFailures;
        try {
            previousFailures = localTracker.readFailedTests();
        } catch (Exception e) {
            report.warn("Lecture failed-tests.txt impossible : " + e.getMessage());
            previousFailures = java.util.Collections.emptyList();
        }

        if (!previousFailures.isEmpty()) {
            // RETRY PUR : on remplace la selection par les seuls echecs precedents.
            affectedTests = new LinkedHashSet<>(previousFailures);
            report.log("  MODE RETRY : " + previousFailures.size()
                               + " echec(s) precedent(s) a re-verifier (changements courants ignores)");
            report.log("  (les " + startsCount + " test(s) STARTS reprendront une fois tout vert)");
        } else {
            report.log("  " + affectedTests.size() + " test(s) selectionne(s) par STARTS");
        }

        // Lister les tests selectionnes (tries pour la lisibilite)
        if (!affectedTests.isEmpty()) {
            List<String> sortedTests = new java.util.ArrayList<>(affectedTests);
            java.util.Collections.sort(sortedTests);
            report.log("  Tests selectionnes :");
            for (String t : sortedTests) {
                report.log("    - " + t);
            }
        }

        if (affectedTests.isEmpty()) {
            report.log("[OK] Aucun test affecte. Rien a lancer.");
            report.writeToFile(getProject().getBasedir());
            return;
        }

        // --------------------------------------------------------------------
        // ETAPE 2 - Separation TU / TI
        // --------------------------------------------------------------------
        report.section("Etape 2 : separation TU / TI");
        TestSplitResult split = selector.split(affectedTests);

        // --------------------------------------------------------------------
        // ETAPE 3 - Info BDD (informatif uniquement, ne bloque pas)
        // --------------------------------------------------------------------
        report.section("Etape 3 : configuration BDD");
        dbCheck.logDatabaseInfo();

        // Verification seuil TI
        boolean itOverLimit = split.getItCount() > maxItTests;
        if (itOverLimit) {
            report.log("  [MAX] " + split.getItCount() + " TI > seuil " + maxItTests
                               + " -> Failsafe non lance");
            report.log("        Lancez : mvn failsafe:integration-test pour les TI");
        }

        // --------------------------------------------------------------------
        // ETAPE 4 + 5 - Surefire (TU) + Failsafe (TI)
        // Le fichier framework2.properties est patche pour la duree des tests
        // (ACTIVER_HIBERNATE=false) puis restaure dans tous les cas.
        // --------------------------------------------------------------------
        File propsFile = new File(propertiesFile);
        if (!propsFile.isAbsolute()) {
            propsFile = new File(getProject().getBasedir(), propertiesFile);
        }

        boolean surefireOk  = true;
        boolean failsafeOk  = true;
        boolean failsafeRan = false;

        // En multi-module, le patch + restauration sont geres par starts:prepare
        // et le shell. skipPropertiesPatch=true => on ne patche/restaure pas ici.
        PropertiesGuard guard = skipPropertiesPatch
                ? null
                : new PropertiesGuard(propsFile, report);
        try {
            if (guard != null) {
                guard.disable("ACTIVER_HIBERNATE");
            }

            // ----------------------------------------------------------------
            // Pre-requis (compilation reactor + config-dev) : desormais
            // assures par le script shell AVANT l'appel a STARTS, via
            //   mvn -pl <modules> -am install -DskipTests
            // Le plugin ne compile plus : il suppose les .class deja presents.
            // ----------------------------------------------------------------

            // ----------------------------------------------------------------
            // ETAPE 4 - Surefire (TU)
            // ----------------------------------------------------------------
            if (split.hasUnitTests()) {
                report.section("Etape 4 : Surefire - " + split.getUnitCount() + " TU");
                surefireOk = runner.invokeSurefire(split.getUnitTests());
                report.log(surefireOk ? "  [OK] Surefire OK" : "  [FAIL] Surefire ECHEC");
            } else {
                report.section("Etape 4 : Surefire - aucun TU");
            }

            // ----------------------------------------------------------------
            // ETAPE 5 - Failsafe (TI)
            // ----------------------------------------------------------------
            if (split.hasItTests() && !itOverLimit) {
                report.section("Etape 5 : Failsafe - " + split.getItCount() + " TI");
                failsafeOk  = runner.invokeFailsafe(split.getItTests());
                failsafeRan = true;
                report.log(failsafeOk ? "  [OK] Failsafe OK" : "  [FAIL] Failsafe ECHEC");
            } else {
                report.section("Etape 5 : Failsafe - "
                                       + (itOverLimit ? "seuil depasse" : "aucun TI"));
            }
        } finally {
            // Restaure framework2.properties seulement si on l'a patche ici
            // (mode mono-module). En multi-module, c'est le shell qui restaure.
            if (guard != null) {
                guard.close();
            }
        }

        // --------------------------------------------------------------------
        // Sauvegarde des tests en echec dans le .starts LOCAL du module
        // (reutilise localTracker declare plus haut). En multi-module, le shell
        // agrege ensuite tous les failed-tests.txt locaux dans le workDir.
        // --------------------------------------------------------------------
        try {
            List<String> failed = localTracker.recordFailuresFromReports(
                    new File(getProject().getBuild().getDirectory()));
            if (!failed.isEmpty()) {
                report.log("");
                report.log("  " + failed.size() + " test(s) en echec sauvegarde(s) dans "
                                   + localTracker.getReportFile().getAbsolutePath());
            }
        } catch (Exception e) {
            report.warn("Impossible de sauvegarder les tests en echec : " + e.getMessage());
        }

        // --------------------------------------------------------------------
        // ETAPE 6 - Bilan + mise a jour checksums
        // --------------------------------------------------------------------
        report.printSummary(
                split.getUnitCount(), surefireOk,
                split.getItCount(),   failsafeOk,
                failsafeRan, itOverLimit, null);

        boolean allOk = surefireOk && (!failsafeRan || failsafeOk);
        if (updateChecksums && allOk) {
            report.section("Etape 6 : mise a jour des checksums STARTS");
            selector.updateChecksums();
        } else if (!allOk) {
            report.log("");
            report.log("  [WARN] Checksums NON mis a jour (des tests ont echoue)");
            report.log("         Corrigez les tests puis relancez.");
        }

        long end = System.currentTimeMillis();
        report.log("");
        report.log("  Duree totale : " + Writer.millsToSeconds(end - start) + " s");
        report.writeToFile(getProject().getBasedir());

        // Propager l'echec a Maven
        if (!allOk) {
            throw new MojoExecutionException(
                    "Des tests ont echoue. Consultez les logs ci-dessus.");
        }
    }

    /**
     * Convertit une URL fichier ZLC en FQN Java lisible.
     * Exemple : file:/D:/.../target/classes/com%5cfoo%5cBar.class -> com.foo.Bar
     */
    private static String urlToFqn(String url) {
        if (url == null) {
            return "?";
        }
        int testIdx    = url.indexOf("/test-classes/");
        int classesIdx = url.indexOf("/classes/");
        int start;
        if (testIdx >= 0) {
            start = testIdx + "/test-classes/".length();
        } else if (classesIdx >= 0) {
            start = classesIdx + "/classes/".length();
        } else {
            return url;
        }
        String rel = url.substring(start);
        if (rel.endsWith(".class")) {
            rel = rel.substring(0, rel.length() - 6);
        }
        return rel.replace("%5c", ".").replace("%5C", ".")
                .replace("%2f", ".").replace("%2F", ".")
                .replace('/', '.').replace('\\', '.');
    }
}