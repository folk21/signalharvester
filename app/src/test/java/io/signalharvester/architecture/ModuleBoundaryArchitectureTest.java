package io.signalharvester.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Protects functional-module dependency direction, published API purity, and adapter boundaries across
 * the backend; representative published contracts include
 * {@link io.signalharvester.configuration.api.SourceConfigurationProvider}.
 *
 * <p>Related specification: {@code backend-project-structure}.</p>
 */
class ModuleBoundaryArchitectureTest {

    private static final Map<String, String> MODULE_PACKAGES = Map.of(
            "configuration", "io.signalharvester.configuration",
            "collection", "io.signalharvester.collection",
            "analysis", "io.signalharvester.analysis",
            "results", "io.signalharvester.results",
            "event-observation", "io.signalharvester.eventobservation",
            "security", "io.signalharvester.security");

    private static final List<String> API_FORBIDDEN_DEPENDENCY_PREFIXES = List.of(
            "io.micronaut.",
            "jakarta.",
            "javax.",
            "java.sql",
            "org.flywaydb.",
            "org.slf4j.",
            "com.fasterxml.jackson.");

    /**
     * Cross module dependencies use only published API packages.
     */
    @Test
    void crossModuleDependenciesUseOnlyPublishedApiPackages() {
        JavaClasses classes = functionalModuleClasses();

        List<String> violations = new ArrayList<>();
        for (var origin : classes) {
            String originModule = owningModule(origin.getPackageName());
            if (originModule == null) {
                continue;
            }
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                String targetPackage = dependency.getTargetClass().getPackageName();
                String targetModule = owningModule(targetPackage);
                if (targetModule == null || targetModule.equals(originModule)) {
                    continue;
                }
                if (!isPublishedApiPackage(targetModule, targetPackage)) {
                    violations.add(dependency.getDescription());
                }
            }
        }

        assertTrue(
                violations.isEmpty(),
                () -> "Cross-module Java dependencies must target the providing module's api package:\n"
                        + String.join("\n", violations));
    }

    /**
     * Functional module dependency graph is acyclic.
     */
    @Test
    void functionalModuleDependencyGraphIsAcyclic() {
        JavaClasses classes = functionalModuleClasses();
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        MODULE_PACKAGES.keySet().forEach(module -> graph.put(module, new LinkedHashSet<>()));

        for (var origin : classes) {
            String originModule = owningModule(origin.getPackageName());
            if (originModule == null) {
                continue;
            }
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                String targetModule = owningModule(dependency.getTargetClass().getPackageName());
                if (targetModule != null && !targetModule.equals(originModule)) {
                    graph.get(originModule).add(targetModule);
                }
            }
        }

        List<String> cycles = findCycles(graph);
        assertTrue(
                cycles.isEmpty(),
                () -> "Functional module dependency graph must remain acyclic:\n" + String.join("\n", cycles));
    }

    /**
     * Published API packages stay free of owning internals and framework types.
     */
    @Test
    void publishedApiPackagesStayFreeOfOwningInternalsAndFrameworkTypes() {
        JavaClasses classes = functionalModuleClasses();
        List<String> violations = new ArrayList<>();

        for (var origin : classes) {
            String originModule = owningModule(origin.getPackageName());
            if (originModule == null || !isPublishedApiPackage(originModule, origin.getPackageName())) {
                continue;
            }

            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                String targetPackage = dependency.getTargetClass().getPackageName();
                String targetModule = owningModule(targetPackage);

                if (originModule.equals(targetModule) && !isPublishedApiPackage(originModule, targetPackage)) {
                    violations.add(dependency.getDescription());
                    continue;
                }

                if (API_FORBIDDEN_DEPENDENCY_PREFIXES.stream().anyMatch(targetPackage::startsWith)) {
                    violations.add(dependency.getDescription());
                }
            }
        }

        assertTrue(
                violations.isEmpty(),
                () -> "Published module APIs must not expose module internals or framework/persistence types:\n"
                        + String.join("\n", violations));
    }

    /**
     * HTTP adapters do not depend directly on persistence.
     */
    @Test
    void httpAdaptersDoNotDependDirectlyOnPersistence() {
        JavaClasses classes = functionalModuleClasses();
        List<String> violations = new ArrayList<>();

        for (var origin : classes) {
            String originModule = owningModule(origin.getPackageName());
            if (originModule == null || !isHttpPackage(originModule, origin.getPackageName())) {
                continue;
            }

            String persistencePackage = MODULE_PACKAGES.get(originModule) + ".persistence";
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                String targetPackage = dependency.getTargetClass().getPackageName();
                if (targetPackage.equals(persistencePackage) || targetPackage.startsWith(persistencePackage + ".")) {
                    violations.add(dependency.getDescription());
                }
            }
        }

        assertTrue(
                violations.isEmpty(),
                () -> "HTTP adapters must depend on application/module boundaries rather than persistence directly:\n"
                        + String.join("\n", violations));
    }

    /**
     * Functional modules do not depend on application composition root.
     */
    @Test
    void functionalModulesDoNotDependOnApplicationCompositionRoot() {
        JavaClasses classes = functionalModuleClasses();
        List<String> violations = new ArrayList<>();

        for (var origin : classes) {
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                if (dependency.getTargetClass().getName().equals("io.signalharvester.Application")) {
                    violations.add(dependency.getDescription());
                }
            }
        }

        assertTrue(
                violations.isEmpty(),
                () -> "Functional modules must not depend on the app composition root:\n"
                        + String.join("\n", violations));
    }

    private static JavaClasses functionalModuleClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(MODULE_PACKAGES.values().toArray(String[]::new));
    }

    private static boolean isPublishedApiPackage(String module, String packageName) {
        String publishedApiPackage = MODULE_PACKAGES.get(module) + ".api";
        return packageName.equals(publishedApiPackage) || packageName.startsWith(publishedApiPackage + ".");
    }

    private static boolean isHttpPackage(String module, String packageName) {
        String httpPackage = MODULE_PACKAGES.get(module) + ".http";
        return packageName.equals(httpPackage) || packageName.startsWith(httpPackage + ".");
    }

    private static String owningModule(String packageName) {
        return MODULE_PACKAGES.entrySet().stream()
                .filter(entry -> packageName.equals(entry.getValue())
                        || packageName.startsWith(entry.getValue() + "."))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private static List<String> findCycles(Map<String, Set<String>> graph) {
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        Deque<String> path = new ArrayDeque<>();
        Set<String> uniqueCycles = new LinkedHashSet<>();

        for (String module : graph.keySet()) {
            findCycles(module, graph, visited, visiting, path, uniqueCycles);
        }
        return List.copyOf(uniqueCycles);
    }

    private static void findCycles(
            String module,
            Map<String, Set<String>> graph,
            Set<String> visited,
            Set<String> visiting,
            Deque<String> path,
            Set<String> cycles) {
        if (visited.contains(module)) {
            return;
        }
        if (!visiting.add(module)) {
            cycles.add(formatCycle(path, module));
            return;
        }

        path.addLast(module);
        for (String target : graph.getOrDefault(module, Set.of())) {
            if (visiting.contains(target)) {
                cycles.add(formatCycle(path, target));
            } else {
                findCycles(target, graph, visited, visiting, path, cycles);
            }
        }
        path.removeLast();
        visiting.remove(module);
        visited.add(module);
    }

    private static String formatCycle(Deque<String> path, String repeatedModule) {
        List<String> cycle = new ArrayList<>();
        boolean include = false;
        for (String module : path) {
            if (module.equals(repeatedModule)) {
                include = true;
            }
            if (include) {
                cycle.add(module);
            }
        }
        cycle.add(repeatedModule);
        return String.join(" -> ", cycle);
    }
}
