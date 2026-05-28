/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.jdeps;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

import edu.illinois.starts.constants.StartsConstants;
import edu.illinois.starts.helpers.Writer;
import edu.illinois.starts.util.Logger;
import edu.illinois.starts.util.Pair;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Finds tests affected by a change but does not run them.
 */
@Mojo(name = "select", requiresDirectInvocation = true, requiresDependencyResolution = ResolutionScope.TEST)
@Execute(phase = LifecyclePhase.TEST_COMPILE)
public class SelectMojo extends DiffMojo implements StartsConstants {

    /**
     * Set this to "true" to update test dependencies on disk. The default value of
     * "false" is useful for "dry runs" where one may want to see the affected
     * tests, without updating test dependencies.
     * Note: if set to "true", this also writes the "selected-tests" file and
     * updates checksums, making it suitable for a final validation step.
     */
    @Parameter(property = "updateSelectChecksums", defaultValue = FALSE)
    private boolean updateSelectChecksums;

    /**
     * Set this to "true" to write the affected tests to the "selected-tests" file
     * on disk WITHOUT updating the ZLC checksums.
     *
     * This is the recommended option for a two-step workflow:
     *   1. mvn starts:select -DwriteSelectedTestsFile=true
     *      → writes .starts/selected-tests, checksums unchanged
     *      → can be re-run multiple times, always returns the same result
     *   2. ./run-tests-archi.sh
     *      → reads .starts/selected-tests and runs Surefire + Failsafe
     *   3. (once tests pass) mvn starts:select -DupdateSelectChecksums=true
     *      → updates checksums for the next selection cycle
     *
     * Note: if "updateSelectChecksums" is also set to "true", it takes precedence:
     * checksums will be updated and this flag has no additional effect.
     */
    @Parameter(property = "writeSelectedTestsFile", defaultValue = FALSE)
    private boolean writeSelectedTestsFile;

    private Logger logger;

    public void execute() throws MojoExecutionException {
        Logger.getGlobal().setLoggingLevel(Level.parse(loggingLevel));
        logger = Logger.getGlobal();
        long start = System.currentTimeMillis();
        Set<String> affectedTests = computeAffectedTests();
        printResult(affectedTests, "AffectedTests");
        long end = System.currentTimeMillis();
        logger.log(Level.FINE, PROFILE_RUN_MOJO_TOTAL + Writer.millsToSeconds(end - start));
        logger.log(Level.FINE, PROFILE_TEST_RUNNING_TIME + 0.0);
    }

    private Set<String> computeAffectedTests() throws MojoExecutionException {
        setIncludesExcludes();
        List<String> allTestsList = getTestClasses(CHECK_IF_ALL_AFFECTED);
        Set<String> allTests = new HashSet<>(allTestsList);
        Set<String> affectedTests = new HashSet<>(allTests);

        Pair<Set<String>, Set<String>> data = computeChangeData(false);
        Set<String> nonAffectedTests = data == null ? new HashSet<String>() : data.getKey();
        affectedTests.removeAll(nonAffectedTests);

        if (allTests.equals(nonAffectedTests)) {
            logger.log(Level.INFO, STARS_RUN_STARS);
            logger.log(Level.INFO, NO_TESTS_ARE_SELECTED_TO_RUN);
        }

        long startUpdate = System.currentTimeMillis();

        if (updateSelectChecksums) {
            // Mise à jour complète : écrit selected-tests ET met à jour les checksums ZLC.
            // À utiliser une fois les tests validés pour préparer la prochaine sélection.
            logger.log(Level.INFO, "STARTS: updating checksums and writing selected-tests (updateSelectChecksums=true)");
            updateForNextRun(nonAffectedTests);
        } else if (writeSelectedTestsFile) {
            // Écrit selected-tests et all-tests sur disque SANS toucher aux checksums ZLC.
            // → Peut être relancé autant de fois que nécessaire, le résultat reste stable.
            // → Idéal pour alimenter un script externe (run-tests-archi.sh, run-tests-ecore.sh).
            logger.log(Level.INFO, "STARTS: writing selected-tests to disk (checksums NOT updated)");
            Writer.writeToFile(new ArrayList<>(allTestsList), "all-tests", getArtifactsDir());
            Writer.writeToFile(new ArrayList<>(affectedTests), "selected-tests", getArtifactsDir());
            logger.log(Level.INFO, "STARTS: selected-tests written → "
                    + getArtifactsDir() + "/selected-tests"
                    + " (" + affectedTests.size() + " tests)");
        } else {
            // Mode dry-run par défaut : affiche les tests en console uniquement,
            // sans écrire de fichier ni mettre à jour les checksums.
            logger.log(Level.FINE, "STARTS: dry-run mode, no file written "
                    + "(use -DwriteSelectedTestsFile=true to write selected-tests)");
        }

        long endUpdate = System.currentTimeMillis();
        logger.log(Level.FINE, PROFILE_STARTS_MOJO_UPDATE_TIME + Writer.millsToSeconds(endUpdate - startUpdate));
        return affectedTests;
    }
}