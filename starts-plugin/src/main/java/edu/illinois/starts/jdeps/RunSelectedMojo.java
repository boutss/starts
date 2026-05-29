/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.jdeps;

import java.io.File;
import java.util.Set;
import java.util.logging.Level;

import edu.illinois.starts.constants.StartsConstants;
import edu.illinois.starts.helpers.Writer;
import edu.illinois.starts.jdeps.runner.DatabaseChecker;
import edu.illinois.starts.jdeps.runner.MavenTestRunner;
import edu.illinois.starts.jdeps.runner.PropertiesGuard;
import edu.illinois.starts.jdeps.runner.RunReport;
import edu.illinois.starts.jdeps.runner.TestSelector;
import edu.illinois.starts.jdeps.runner.TestSplitResult;
import edu.illinois.starts.util.Logger;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
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
@Execute(phase = LifecyclePhase.TEST_COMPILE)
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

    // =========================================================================
    // Point d'entree
    // =========================================================================

    public void execute() throws MojoExecutionException {
        Logger.getGlobal().setLoggingLevel(Level.parse(loggingLevel));
        Logger logger = Logger.getGlobal();
        long start = System.currentTimeMillis();

        // -- Collaborateurs --------------------------------------------------
        RunReport       report   = new RunReport(logger, logsDir, getProject().getArtifactId());
        TestSelector    selector = new TestSelector(this, report);
        DatabaseChecker dbCheck  = new DatabaseChecker(propertiesFile, getProject().getBasedir(), report);
        MavenTestRunner runner   = new MavenTestRunner(
                getProject(), report, configDevPomPath, initDbScriptPath);

        // -- En-tete ---------------------------------------------------------
        report.log("");
        report.separator();
        report.log("  STARTS : run-selected");
        report.separator();

        // --------------------------------------------------------------------
        // ETAPE 1 - Calcul des tests affectes
        // --------------------------------------------------------------------
        report.section("Etape 1 : calcul des tests affectes");
        Set<String> affectedTests = selector.computeAffectedTests();

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
        File propsFile = new File( propertiesFile);
        if (!propsFile.isAbsolute()) {
            propsFile = new File(getProject().getBasedir(), propertiesFile);
        }

        boolean surefireOk  = true;
        boolean failsafeOk  = true;
        boolean failsafeRan = false;

        try (PropertiesGuard guard = new PropertiesGuard(propsFile, report)) {
            guard.disable("ACTIVER_HIBERNATE");

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
        } // <- PropertiesGuard.close() restaure framework2.properties ici

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
}
