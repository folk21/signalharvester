package io.signalharvester.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Guards synchronous Java dependencies between functional modules. */
class ModuleBoundaryArchitectureTest {

    private static final Map<String, String> MODULE_PACKAGES = Map.of(
            "configuration", "io.signalharvester.configuration",
            "collection", "io.signalharvester.collection",
            "analysis", "io.signalharvester.analysis",
            "results", "io.signalharvester.results",
            "event-observation", "io.signalharvester.eventobservation");

    @Test
    void crossModuleDependenciesUseOnlyPublishedApiPackages() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(MODULE_PACKAGES.values().toArray(String[]::new));

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
                String publishedApiPackage = MODULE_PACKAGES.get(targetModule) + ".api";
                if (!targetPackage.equals(publishedApiPackage)
                        && !targetPackage.startsWith(publishedApiPackage + ".")) {
                    violations.add(dependency.getDescription());
                }
            }
        }

        assertTrue(
                violations.isEmpty(),
                () -> "Cross-module Java dependencies must target the providing module's api package:\n"
                        + String.join("\n", violations));
    }

    private static String owningModule(String packageName) {
        return MODULE_PACKAGES.entrySet().stream()
                .filter(entry -> packageName.equals(entry.getValue())
                        || packageName.startsWith(entry.getValue() + "."))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }
}
