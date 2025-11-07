package efluid;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Permet de comprendre les ponts des dépendances pour essayer de ne pas tout tirer.
 *
 * Exemple argument : D:\java\workspaces\developpement_dev\eldap\app\.starts\deps.zlc "com%5chermes%5carc%5cgestionldap%5cbusinessobject%5cFormulaire.class"
 */

public class ZlcBridges {

    private static final Pattern DATA_LINE = Pattern.compile("^(.+?)\\s+([0-9]+(?:,[0-9]+)*)\\s*$");

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java ZlcBridges <deps.zlc> <targetKeyOrSuffix>");
            System.err.println("Ex:    java ZlcBridges deps.zlc \"/com/hermes/arc/gestionldap/businessobject/Formulaire.class\"");
            System.exit(1);
        }
        Path zlcPath = Paths.get(args[0]);
        String targetKeyOrSuffix = args[1];

        ZlcContent content = loadZlc(zlcPath);

        // Trouver la clé exacte de la classe cible (match direct puis endsWith).
        String matchedKey = resolveClassKey(content.classToIndices.keySet(), targetKeyOrSuffix);
        if (matchedKey == null) {
            System.err.println("Target key not found in deps.zlc (tried endsWith): " + targetKeyOrSuffix);
            System.exit(2);
        }

        // Ensemble de tests de la cible
        Set<Integer> targetSet = content.classToIndices.getOrDefault(matchedKey, Collections.emptySet());
        if (targetSet.isEmpty()) {
            System.out.println("No tests mapped to target: " + matchedKey);
            return;
        }

        // Calcul des overlaps et du Jaccard
        List<BridgeRow> bridges = new ArrayList<>();
        for (Map.Entry<String, Set<Integer>> entry : content.classToIndices.entrySet()) {
            String otherClass = entry.getKey();
            if (otherClass.equals(matchedKey)) {
                continue;
            }
            Set<Integer> otherSet = entry.getValue();
            int inter = intersectCount(targetSet, otherSet);
            if (inter == 0) {
                continue;
            }
            int union = targetSet.size() + otherSet.size() - inter;
            double jaccard = union == 0 ? 0.0 : (double) inter / (double) union;
            bridges.add(new BridgeRow(otherClass, inter, jaccard, otherSet.size()));
        }

        bridges.sort(Comparator
                .comparingInt(BridgeRow::overlap).reversed()
                .thenComparingDouble(BridgeRow::jaccard).reversed()
                .thenComparingInt(BridgeRow::otherSize).reversed());

        // Top classes "ponts"
        int limit = Math.min(100, bridges.size());
        System.out.println("=== Top classes qui partagent des tests avec la cible (ponts) ===");
        System.out.printf("Target: %s | tests(target)=%d | totalClasses=%d%n",
                matchedKey, targetSet.size(), content.classToIndices.size());
        for (int rowIndex = 0; rowIndex < limit; rowIndex++) {
            BridgeRow row = bridges.get(rowIndex);
            System.out.printf("%4d) overlap=%5d | jaccard=%6.3f | size(other)=%6d | %s%n",
                    rowIndex + 1, row.overlap, row.jaccard, row.otherSize, row.className);
        }

        // Agrégation par préfixe de package (3 segments)
        Map<String, Integer> packageOverlap = new HashMap<>();
        for (BridgeRow row : bridges) {
            String pkg = packagePrefix3(row.className);
            packageOverlap.merge(pkg, row.overlap, Integer::sum);
        }
        List<Map.Entry<String, Integer>> packageSorted = new ArrayList<>(packageOverlap.entrySet());
        packageSorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());

        System.out.println();
        System.out.println("=== Packages qui relient le plus la cible aux tests (somme des overlaps) ===");
        for (int i = 0; i < Math.min(50, packageSorted.size()); i++) {
            Map.Entry<String, Integer> e = packageSorted.get(i);
            System.out.printf("%4d) sumOverlap=%6d | %s%n", i + 1, e.getValue(), e.getKey());
        }

        // Petit rappel utile
        System.out.println();
        System.out.println("NOTE: privilégie le pruning par packages entiers (logging, mocks, JPA, etc.) plutôt que par classes isolées.");
    }

    private static ZlcContent loadZlc(Path path) throws IOException {
        List<String> testsHeader = new ArrayList<>(1024);
        Map<String, Set<Integer>> classToIndices = new HashMap<>(1 << 18);

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            boolean inData = false;

            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (!inData) {
                    if (DATA_LINE.matcher(trimmed).matches()) {
                        inData = true;
                        parseDataLine(trimmed, classToIndices);
                    } else {
                        testsHeader.add(trimmed);
                    }
                } else {
                    if (DATA_LINE.matcher(trimmed).matches()) {
                        parseDataLine(trimmed, classToIndices);
                    }
                }
            }
        }
        return new ZlcContent(testsHeader, classToIndices);
    }

    private static void parseDataLine(String line, Map<String, Set<Integer>> classToIndices) {
        // "<classKey> <csvIndices>"
        int spaceIndex = line.indexOf(' ');
        if (spaceIndex <= 0 || spaceIndex == line.length() - 1) {
            return;
        }
        String classKey = line.substring(0, spaceIndex);
        String csv = line.substring(spaceIndex + 1);
        Set<Integer> indices = new HashSet<>();
        int value = 0;
        boolean inNum = false;
        for (int pos = 0; pos < csv.length(); pos++) {
            char ch = csv.charAt(pos);
            if (ch == ',') {
                if (inNum) {
                    indices.add(value);
                    value = 0;
                    inNum = false;
                }
            } else if (ch >= '0' && ch <= '9') {
                inNum = true;
                value = value * 10 + (ch - '0');
            }
        }
        if (inNum) {
            indices.add(value);
        }
        classToIndices.put(classKey, indices);
    }

    private static String resolveClassKey(Set<String> keys, String targetKeyOrSuffix) {
        if (keys.contains(targetKeyOrSuffix)) {
            return targetKeyOrSuffix;
        }
        // Essayer des variantes communes (slashes vs points, etc.)
        String alt1 = targetKeyOrSuffix.replace("\\", "/");
        String alt2 = alt1.startsWith("/") ? alt1.substring(1) : "/" + alt1;
        for (String key : keys) {
            if (key.endsWith(targetKeyOrSuffix) || key.endsWith(alt1) || key.endsWith(alt2)) {
                return key;
            }
        }
        return null;
    }

    private static int intersectCount(Set<Integer> a, Set<Integer> b) {
        if (a.size() > b.size()) {
            Set<Integer> tmp = a;
            a = b;
            b = tmp;
        }
        int count = 0;
        for (Integer value : a) {
            if (b.contains(value)) {
                count++;
            }
        }
        return count;
    }

    private static String packagePrefix3(String classKey) {
        String normalized = classKey.replace('\\', '/');
        int idx = normalized.indexOf("/classes/");
        if (idx >= 0) {
            normalized = normalized.substring(idx + "/classes/".length());
        }
        normalized = normalized.replace('/', '.');
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        String[] parts = normalized.split("\\.");
        if (parts.length >= 3) {
            return parts[0] + "." + parts[1] + "." + parts[2];
        }
        if (parts.length > 0) {
            return String.join(".", parts);
        }
        return normalized;
    }

    private static final class ZlcContent {
        final List<String> testsHeader;
        final Map<String, Set<Integer>> classToIndices;
        ZlcContent(List<String> testsHeader, Map<String, Set<Integer>> classToIndices) {
            this.testsHeader = testsHeader;
            this.classToIndices = classToIndices;
        }
    }

    private static final class BridgeRow {
        final String className;
        final int overlap;
        final double jaccard;
        final int otherSize;
        BridgeRow(String className, int overlap, double jaccard, int otherSize) {
            this.className = className;
            this.overlap = overlap;
            this.jaccard = jaccard;
            this.otherSize = otherSize;
        }
        int overlap() { return overlap; }
        double jaccard() { return jaccard; }
        int otherSize() { return otherSize; }
    }
}
