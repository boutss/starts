/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.helpers;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Collectors;

import edu.illinois.starts.constants.StartsConstants;
import edu.illinois.starts.data.ZLCData;
import edu.illinois.starts.data.ZLCFileContent;
import edu.illinois.starts.data.ZLCFormat;
import edu.illinois.starts.util.ChecksumUtil;
import edu.illinois.starts.util.Logger;
import edu.illinois.starts.util.Pair;
import org.ekstazi.util.Types;

/**
 * Utility methods for dealing with the .zlc format.
 */
public class ZLCHelper implements StartsConstants {
    public static ArrayList<String> testsList;
    public static final String zlcFile = "deps.zlc";
    public static final String STAR_FILE = "file:*";
    private static final Logger LOGGER = Logger.getGlobal();
    private static final String NOEXISTING_ZLCFILE_FIRST_RUN = "@NoExistingZLCFile. First Run?";

    public ZLCHelper() {
    }

// TODO: Uncomment and fix this method. The problem is that it does not track newly added tests correctly
//    public static void updateZLCFile(Map<String, Set<String>> testDeps, ClassLoader loader,
//                                     String artifactsDir, Set<String> changed) {
//        long start = System.currentTimeMillis();
//        File file = new File(artifactsDir, zlcFile);
//        if (! file.exists()) {
//            Set<ZLCData> zlc = createZLCData(testDeps, loader);
//            Writer.writeToFile(zlc, zlcFile, artifactsDir);
//        } else {
//            Set<ZLCData> zlcData = new HashSet<>();
//            if (zlcDataMap != null) {
//                for (ZLCData data : zlcDataMap.values()) {
//                    String extForm = data.getUrl().toExternalForm();
//                    if (changed.contains(extForm)) {
//                         we need to update tests for this zlcData before adding
//                        String fqn = Writer.toFQN(extForm);
//                        Set<String> tests = new HashSet<>();
//                        if (testDeps.keySet().contains(fqn)) {
//                             a test class changed, it affects on itself
//                            tests.add(fqn);
//                        }
//                        for (String test : testDeps.keySet()) {
//                            if (testDeps.get(test).contains(fqn)) tests.add(test);
//                        }
//                        if (tests.isEmpty()) {
//                             this dep no longer has ant tests depending on it???
//                            continue;
//                        }
//                        data.setTests(tests);
//                    }
//                    zlcData.add(data);
//                }
//            }
//            Writer.writeToFile(zlcData, zlcFile, artifactsDir);
//        }
//        long end = System.currentTimeMillis();
//        System.out.println(TIME_UPDATING_CHECKSUMS + (end - start) + MS);
//    }

    public static void updateZLCFile(Map<String, Set<String>> testDeps, ClassLoader loader,
                                     String artifactsDir, Set<String> unreached, boolean useThirdParty,
                                     ZLCFormat format) {
        // TODO: Optimize this by only recomputing the checksum+tests for changed classes and newly added tests
        long start = System.currentTimeMillis();
        LOGGER.log(Level.FINE, "ZLC format: " + format.toString());
        ZLCFileContent zlc = createZLCData(testDeps, loader, useThirdParty, format);
        Writer.writeToFile(zlc, zlcFile, artifactsDir);
        long end = System.currentTimeMillis();
        LOGGER.log(Level.FINE, "[PROFILE] updateForNextRun(updateZLCFile): " + Writer.millsToSeconds(end - start));
    }

    public static ZLCFileContent createZLCData(
            Map<String, Set<String>> testDeps,
            ClassLoader loader,
            boolean useJars,
            ZLCFormat format
    ) {
        long start = System.currentTimeMillis();

        // 1) Liste stable des tests (ordre = indices)
        ArrayList<String> testList = new ArrayList<>(testDeps.keySet());

        // 2) Construire l’index inverse: dep -> BitSet d’indices de tests
        //    (O(total deps) au lieu de O(#tests * #deps uniques) à chaque requête)
        Map<String, BitSet> depToTests = new HashMap<>();
        for (int i = 0; i < testList.size(); i++) {
            String test = testList.get(i);
            Set<String> depsOfTest = testDeps.get(test);
            if (depsOfTest == null || depsOfTest.isEmpty()) {
                continue;
            }
            for (String dep : depsOfTest) {
                depToTests.computeIfAbsent(dep, __ -> new BitSet(testList.size())).set(i);
            }
        }

        // 3) Pour chaque dep connue, calculer (URL, checksum) et matérialiser selon le format
        ChecksumUtil checksumUtil = new ChecksumUtil(true);
        List<ZLCData> zlcData = new ArrayList<>(depToTests.size());

        for (Map.Entry<String, BitSet> e : depToTests.entrySet()) {
            String dep = e.getKey();
            String klas = ChecksumUtil.toClassName(dep);
            if (Types.isIgnorableInternalName(klas)) {
                continue;
            }

            URL url = loader.getResource(klas);
            if (url == null) {
                continue;
            }

            String extForm = url.toExternalForm();
            if (ChecksumUtil.isWellKnownUrl(extForm) || (!useJars && extForm.startsWith("jar:"))) {
                continue;
            }

            String checksum = checksumUtil.computeSingleCheckSum(url);
            BitSet bits = e.getValue();
            int expected = bits.cardinality();

            switch (format) {
                case PLAIN_TEXT:
                    // Matérialiser uniquement les Strings nécessaires
                    Set<String> testsStr = new HashSet<>((int) (expected / 0.75f) + 1);
                    // Astuce perf: matérialiser en tableau pour get(idx) très rapide
                    String[] arr = testList.toArray(String[]::new);
                    for (int idx = bits.nextSetBit(0); idx >= 0; idx = bits.nextSetBit(idx + 1)) {
                        testsStr.add(arr[idx]);
                    }
                    zlcData.add(new ZLCData(url, checksum, format, testsStr, null));
                    break;
                case INDEXED:
                    Set<Integer> testsIdx = new HashSet<>((int) (expected / 0.75f) + 1);
                    for (int idx = bits.nextSetBit(0); idx >= 0; idx = bits.nextSetBit(idx + 1)) {
                        testsIdx.add(idx); // boxing, mais une seule fois et sans contains()
                    }
                    zlcData.add(new ZLCData(url, checksum, format, null, testsIdx));
                    break;
                default:
                    throw new RuntimeException("Unexpected ZLCFormat");
            }
        }

        long end = System.currentTimeMillis();
        LOGGER.log(Level.FINEST, "[TIME]CREATING ZLC FILE: " + (end - start) + MILLISECOND);
        return new ZLCFileContent(testList, zlcData, format);
    }


    public static Pair<Set<String>, Set<String>> getChangedData(String artifactsDir, boolean cleanBytes) {
        long start = System.currentTimeMillis();
        File zlc = new File(artifactsDir, zlcFile);
        if (!zlc.exists()) {
            LOGGER.log(Level.FINEST, NOEXISTING_ZLCFILE_FIRST_RUN);
            return null;
        }
        Set<String> changedClasses = new HashSet<>();
        Set<String> nonAffected = new HashSet<>();
        Set<String> affected = new HashSet<>();
        List<String> starTests = new ArrayList<>();
        ChecksumUtil checksumUtil = new ChecksumUtil(cleanBytes);
        try {
            List<String> zlcLines = Files.readAllLines(zlc.toPath(), Charset.defaultCharset());
            String firstLine = zlcLines.get(0);
            String space = WHITE_SPACE;

            // check whether the first line is for *
            if (firstLine.startsWith(STAR_FILE)) {
                String[] parts = firstLine.split(space);
                starTests = fromCSV(parts[2]);
                zlcLines.remove(0);
            }

            ZLCFormat format = ZLCFormat.PLAIN_TEXT;  // default to plain text
            if (zlcLines.get(0).equals(ZLCFormat.PLAIN_TEXT.toString())) {
                format = ZLCFormat.PLAIN_TEXT;
                zlcLines.remove(0);
            } else if (zlcLines.get(0).equals(ZLCFormat.INDEXED.toString())) {
                format = ZLCFormat.INDEXED;
                zlcLines.remove(0);
            }

            int testsCount = -1;  // on PLAIN_TEXT, testsCount+1 will starts from 0
            if (format == ZLCFormat.INDEXED) {
                try {
                    testsCount = Integer.parseInt(zlcLines.get(0));
                } catch (NumberFormatException nfe) {
                    nfe.printStackTrace();
                }
                testsList = new ArrayList<>(zlcLines.subList(1, testsCount + 1));
                nonAffected.addAll(testsList); // Comme la liste des tests est indexé dans le fichier on la récupère tel quel.
            }

            for (int i = testsCount + 1; i < zlcLines.size(); i++) {
                String line = zlcLines.get(i);
                String[] parts = line.split(space);
                String stringURL = parts[0];
                String oldCheckSum = parts[1];
                List<String> tests = null;
                if (format == ZLCFormat.INDEXED) {
                    BitSet testsBits = (parts.length == 3)
                            ? fromCSVToBitSet(parts[2], testsList.size())
                            : new BitSet();
                    int expected = testsBits.cardinality();
                    List<String> out = new ArrayList<>((int)(expected / 0.75f) + 1);

                    String[] arr = testsList.toArray(String[]::new);

                    for (int idx = testsBits.nextSetBit(0); idx >= 0; idx = testsBits.nextSetBit(idx + 1)) {
                        // optionnel: borne si tu n'as pas garanti la validité des indices
                        if (idx < arr.length) {
                            out.add(arr[idx]);
                        }
                    }
                    tests = out;
                } else {
                    tests = parts.length == 3 ? fromCSV(parts[2]) : new ArrayList<>();
                }
                URL url = new URL(stringURL);
                String newCheckSum = checksumUtil.computeSingleCheckSum(url);
                if (!newCheckSum.equals(oldCheckSum)) {
                    affected.addAll(tests);
                    changedClasses.add(stringURL);
                }
                if (newCheckSum.equals("-1")) {
                    // a class was deleted or auto-generated, no need to track it in zlc
                    LOGGER.log(Level.FINEST, "Ignoring: " + url);
                    continue;
                }
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        if (!changedClasses.isEmpty()) {
            // there was some change so we need to add all tests that reach star, if any
            affected.addAll(starTests);
        }
        nonAffected.removeAll(affected);
        long end = System.currentTimeMillis();
        LOGGER.log(Level.FINEST, TIME_COMPUTING_NON_AFFECTED + (end - start) + MILLISECOND);
        return new Pair<>(nonAffected, changedClasses);
    }

    private static List<String> fromCSV(String tests) {
        return Arrays.asList(tests.split(COMMA));
    }

    private static BitSet fromCSVToBitSet(CharSequence csv, int maxIndexExclusive) {
        BitSet bs = new BitSet(maxIndexExclusive); // 1 bit par index
        int csvlength = csv.length();
        int index = 0;
        boolean inNum = false;
        for (int i = 0; i < csvlength; i++) {
            char charAt = csv.charAt(i);
            if (charAt == ',') {
                if (inNum) {
                    bs.set(index);
                    index = 0;
                    inNum = false;
                }
            } else {
                // optionnel: ignorer espaces
                if (charAt >= '0' && charAt <= '9') {
                    inNum = true;
                    index = index * 10 + (charAt - '0');
                }
            }
        }
        if (inNum) {
            bs.set(index);
        }
        return bs;
    }

    public static Set<String> getExistingClasses(String artifactsDir) {
        Set<String> existingClasses = new HashSet<>();
        long start = System.currentTimeMillis();
        File zlc = new File(artifactsDir, zlcFile);
        if (!zlc.exists()) {
            LOGGER.log(Level.FINEST, NOEXISTING_ZLCFILE_FIRST_RUN);
            return existingClasses;
        }
        try {
            List<String> zlcLines = Files.readAllLines(zlc.toPath(), Charset.defaultCharset());
            for (String line : zlcLines) {
                if (line.startsWith("file")) {
                    existingClasses.add(Writer.urlToFQN(line.split(WHITE_SPACE)[0]));
                }
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        long end = System.currentTimeMillis();
        LOGGER.log(Level.FINEST, "[TIME]COMPUTING EXISTING CLASSES: " + (end - start) + MILLISECOND);
        return existingClasses;
    }
}
