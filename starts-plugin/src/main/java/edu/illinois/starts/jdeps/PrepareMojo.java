/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.jdeps;

import java.io.File;
import java.util.logging.Level;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import edu.illinois.starts.constants.StartsConstants;
import edu.illinois.starts.jdeps.runner.MavenTestRunner;
import edu.illinois.starts.jdeps.runner.PropertiesGuard;
import edu.illinois.starts.jdeps.runner.RunReport;
import edu.illinois.starts.util.Logger;

/**
 * Pre-requis GLOBAUX, a executer UNE seule fois avant une serie de
 * {@code starts:run-selected} multi-modules :
 * <ul>
 *   <li>Patch de {@code framework2.properties} : {@code ACTIVER_HIBERNATE=false}</li>
 *   <li>Initialisation de la BDD locale (script {@code UL-base-validation.sh})</li>
 * </ul>
 *
 * <p>La restauration de {@code framework2.properties} est faite par le script
 * shell apres la serie de tests (le patch est volontairement NON restaure ici,
 * car les modules tournent entre prepare et la restauration finale).
 *
 * <p>Comme tous les modules partagent une seule BDD, cette etape ne doit pas
 * etre repetee par module : {@code run-selected} est appele avec
 * {@code -DskipDbInit=true}.
 */
@Mojo(name = "prepare", requiresDependencyResolution = ResolutionScope.NONE)
public class PrepareMojo extends BaseMojo implements StartsConstants {

    @Parameter(property = "propertiesFile",
               defaultValue = "${project.basedir}/config-dev/src/main/properties2/framework2.properties")
    private String propertiesFile;

    @Parameter(property = "initDbScriptPath",
               defaultValue = "${project.basedir}/scripts/init_bdd_localhost.sh")
    private String initDbScriptPath;

    @Parameter(property = "logsDir",
               defaultValue = "${project.basedir}/scripts/starts/logs")
    private String logsDir;

    /**
     * Si true, ne patche pas framework2.properties (utile si deja patche).
     */
    @Parameter(property = "skipPropertiesPatch", defaultValue = "false")
    private boolean skipPropertiesPatch;

    /**
     * Si true, ne lance pas l'init BDD (utile pour ne faire que le patch).
     */
    @Parameter(property = "skipDbInit", defaultValue = "false")
    private boolean skipDbInit;

    @Override
    public void execute() throws MojoExecutionException {
        Logger.getGlobal().setLoggingLevel(Level.parse(loggingLevel));
        Logger logger = Logger.getGlobal();

        RunReport report = new RunReport(logger, logsDir, getProject().getArtifactId());

        report.log("");
        report.separator();
        report.log("  STARTS : prepare (pre-requis globaux)");
        report.separator();

        // -- 1. Patch framework2.properties ----------------------------------
        if (!skipPropertiesPatch) {
            File propsFile = new File(propertiesFile);
            if (!propsFile.isAbsolute()) {
                propsFile = new File(getProject().getBasedir(), propertiesFile);
            }
            // On patche sans restaurer : la restauration est faite par le shell
            // apres la serie de run-selected. PropertiesGuard sans try-with-resources.
            PropertiesGuard guard = new PropertiesGuard(propsFile, report);
            guard.disable("ACTIVER_HIBERNATE");
            report.log("  framework2.properties patche (ACTIVER_HIBERNATE=false)");
            report.log("  -> sera restaure par le script shell en fin de serie");
        } else {
            report.log("  (patch properties saute : -DskipPropertiesPatch=true)");
        }

        // -- 2. Init BDD -----------------------------------------------------
        if (!skipDbInit) {
            MavenTestRunner runner = new MavenTestRunner(
                    getProject(), report, null, initDbScriptPath, 1, 1, false);
            boolean ok = runner.prepareDatabase();
            if (!ok) {
                throw new MojoExecutionException(
                        "Echec de l'initialisation BDD. Les tests ne peuvent pas etre lances.");
            }
        } else {
            report.log("  (init BDD sautee : -DskipDbInit=true)");
        }

        report.log("");
        report.log("  [OK] Pre-requis globaux prets. Lancer run-selected par module.");
        report.writeToFile(getProject().getBasedir());
    }
}