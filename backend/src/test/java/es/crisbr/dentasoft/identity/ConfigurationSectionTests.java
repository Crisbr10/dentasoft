package es.crisbr.dentasoft.identity;

import static es.crisbr.dentasoft.identity.ConfigurationSection.CLINIC;
import static es.crisbr.dentasoft.identity.ConfigurationSection.PLATFORM;
import static es.crisbr.dentasoft.identity.ConfigurationSection.PRACTITIONERS;
import static es.crisbr.dentasoft.identity.ConfigurationSection.USERS_AND_ROLES;
import static es.crisbr.dentasoft.identity.SectionAccess.EDIT;
import static es.crisbr.dentasoft.identity.SectionAccess.VIEW;
import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.identity.ConfigurationSection.VisibleSection;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The matrix of {@code docs/plan/configuracion.md} («Secciones y permisos»), written out here row by row for the
 * sections that exist so far: a change to the catalog that does not match the document fails here.
 */
class ConfigurationSectionTests {

    static Stream<Arguments> matrix() {
        return Stream.of(
                Arguments.of(
                        ClinicRole.ADMIN_CLINICA,
                        List.of(
                                new VisibleSection(CLINIC, EDIT),
                                new VisibleSection(USERS_AND_ROLES, EDIT),
                                new VisibleSection(PRACTITIONERS, EDIT))),
                Arguments.of(
                        ClinicRole.ODONTOLOGO,
                        List.of(new VisibleSection(CLINIC, VIEW), new VisibleSection(PRACTITIONERS, VIEW))),
                Arguments.of(
                        ClinicRole.HIGIENISTA,
                        List.of(new VisibleSection(CLINIC, VIEW), new VisibleSection(PRACTITIONERS, VIEW))),
                Arguments.of(
                        ClinicRole.RECEPCION,
                        List.of(new VisibleSection(CLINIC, VIEW), new VisibleSection(PRACTITIONERS, VIEW))),
                Arguments.of(
                        ClinicRole.FACTURACION,
                        List.of(new VisibleSection(CLINIC, VIEW), new VisibleSection(PRACTITIONERS, VIEW))));
    }

    @ParameterizedTest
    @MethodSource("matrix")
    void eachClinicRoleSeesExactlyItsSectionsInMenuOrder(ClinicRole role, List<VisibleSection> expected) {
        assertThat(ConfigurationSection.visibleTo(role)).containsExactlyElementsOf(expected);
    }

    @Test
    void everyClinicRoleIsInTheMatrix() {
        assertThat(matrix().map(arguments -> arguments.get()[0]))
                .containsExactlyInAnyOrder((Object[]) ClinicRole.values());
    }

    @Test
    void aPlatformAdministratorSeesOnlyThePlatform() {
        assertThat(ConfigurationSection.visibleToPlatformAdmin()).containsExactly(new VisibleSection(PLATFORM, EDIT));
    }
}
