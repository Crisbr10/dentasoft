package es.crisbr.dentasoft;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModularityTests {

    private static final ApplicationModules MODULES = ApplicationModules.of(DentasoftApplication.class);

    private static final String DOCS_FOLDER = "target/spring-modulith-docs";

    @Test
    void modulesRespectTheirBoundaries() {
        MODULES.verify();
    }

    @Test
    void writesModuleDocumentation() {
        new Documenter(MODULES, Documenter.Options.defaults().withOutputFolder(DOCS_FOLDER)).writeDocumentation();

        assertThat(Path.of(DOCS_FOLDER, "components.puml")).exists();
    }
}
