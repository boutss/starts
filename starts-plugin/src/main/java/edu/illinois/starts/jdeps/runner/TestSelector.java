/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.jdeps.runner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.illinois.starts.constants.StartsConstants;
import edu.illinois.starts.helpers.Writer;
import edu.illinois.starts.jdeps.DiffMojo;
import edu.illinois.starts.util.Pair;
import org.apache.maven.plugin.MojoExecutionException;

/**
 * Calcule les tests affectes par les modifications courantes via STARTS,
 * ecrit selected-tests sur disque (sans mettre a jour les checksums ZLC),
 * et separe les tests en deux familles TU / TI.
 *
 * Le calcul peut etre rejoue plusieurs fois sans effet de bord :
 * les checksums ne sont mis a jour que via updateChecksums().
 */
public class TestSelector {

    private final DiffMojo mojo;
    private final RunReport report;

    public TestSelector(DiffMojo mojo, RunReport report) {
        this.mojo   = mojo;
        this.report = report;
    }

    // -------------------------------------------------------------------------
    // Calcul des tests affectes (sans maj checksums)
    // -------------------------------------------------------------------------

    /**
     * Calcule les tests affectes par les modifications courantes.
     * Ecrit selected-tests et all-tests sur disque SANS toucher aux checksums ZLC.
     * Peut etre appele plusieurs fois de suite sans changer le resultat.
     *
     * @return ensemble des FQN de tests affectes
     */
    public Set<String> computeAffectedTests() throws MojoExecutionException {
        mojo.setIncludesExcludes();
        List<String> allTestsList = mojo.getTestClasses(StartsConstants.CHECK_IF_ALL_AFFECTED);
        Set<String> allTests      = new HashSet<>(allTestsList);
        Set<String> affectedTests = new HashSet<>(allTests);

        Pair<Set<String>, Set<String>> data = mojo.computeChangeData(false);
        Set<String> nonAffectedTests = data == null ? new HashSet<>() : data.getKey();
        affectedTests.removeAll(nonAffectedTests);

        // Ecriture sur disque sans maj checksums
        Writer.writeToFile(new ArrayList<>(allTestsList), "all-tests",    mojo.getArtifactsDir());
        Writer.writeToFile(new ArrayList<>(affectedTests), "selected-tests", mojo.getArtifactsDir());

        report.log("  -> " + affectedTests.size() + " test(s) selectionne(s) sur "
                + allTests.size() + " au total");
        return affectedTests;
    }

    // -------------------------------------------------------------------------
    // Separation TU / TI
    // -------------------------------------------------------------------------

    /**
     * Separe les tests affectes en deux familles selon la convention de nommage :
     * - contient "IT" dans le nom simple -> TI (Failsafe)
     * - sinon                            -> TU (Surefire)
     */
    public TestSplitResult split(Set<String> affectedTests) {
        List<String> unitTests = new ArrayList<>();
        List<String> itTests   = new ArrayList<>();

        for (String fqn : affectedTests) {
            String simpleName = fqn.contains(".")
                    ? fqn.substring(fqn.lastIndexOf('.') + 1)
                    : fqn;
            if (simpleName.contains("IT")) {
                itTests.add(simpleName);
            } else {
                unitTests.add(simpleName);
            }
        }

        report.log("  -> " + unitTests.size() + " TU, " + itTests.size() + " TI");
        return new TestSplitResult(unitTests, itTests);
    }

    // -------------------------------------------------------------------------
    // Mise a jour des checksums (a appeler seulement apres succes)
    // -------------------------------------------------------------------------

    /**
     * Met a jour les checksums ZLC pour le prochain cycle de selection.
     * A appeler uniquement si tous les tests ont passe.
     */
    public void updateChecksums() throws MojoExecutionException {
        Set<String> allTests = new HashSet<>(
                mojo.getTestClasses(StartsConstants.CHECK_IF_ALL_AFFECTED));
        Pair<Set<String>, Set<String>> data = mojo.computeChangeData(false);
        Set<String> nonAffected = data == null ? new HashSet<>() : data.getKey();
        mojo.updateForNextRun(nonAffected);
        report.log("  [OK] Checksums mis a jour");
    }
}
