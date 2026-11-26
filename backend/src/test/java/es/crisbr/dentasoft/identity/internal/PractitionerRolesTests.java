package es.crisbr.dentasoft.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.audit.AuditLogDatabase;
import es.crisbr.dentasoft.identity.ActivePractitioners;
import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.IdentityTestDatabase;
import es.crisbr.dentasoft.identity.PractitionerSpecialty;
import es.crisbr.dentasoft.identity.PractitionerSummary;
import es.crisbr.dentasoft.identity.PractitionerUnavailableException;
import es.crisbr.dentasoft.identity.internal.PractitionerConflictException.Reason;
import es.crisbr.dentasoft.identity.internal.PractitionerRoles.PractitionerData;
import es.crisbr.dentasoft.identity.internal.PractitionerRoles.PractitionerDetails;
import es.crisbr.dentasoft.identity.persistence.PractitionerRoleStatus;
import es.crisbr.dentasoft.tenancy.TenantContext;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

/**
 * Practitioner records of the active clinic (M02-T06): only {@code ADMIN_CLINICA} manages them, a linked record
 * follows its user's membership, and a deactivated practitioner is never offered for new appointments.
 */
@IntegrationTest
class PractitionerRolesTests extends ClinicUsersTestBase {

    @Autowired
    private PractitionerRoles practitioners;

    @Autowired
    private ActivePractitioners activePractitioners;

    // --- Permissions ---

    static Stream<String> managing() {
        return Stream.of("create", "update", "deactivate", "activate");
    }

    private Runnable operation(String name, UUID id) {
        return switch (name) {
            case "create" -> () -> practitioners.create(external("Dr. Nuevo", "28", "4444"));
            case "update" -> () -> practitioners.update(id, external("Dr. Cambiado", "28", "5555"), 0);
            case "deactivate" -> () -> practitioners.deactivate(id, 0);
            case "activate" -> () -> practitioners.activate(id, 0);
            case "list" -> () -> practitioners.list(null, PageRequest.of(0, 20));
            case "get" -> () -> practitioners.get(id);
            case "active" -> activePractitioners::list;
            default -> throw new IllegalArgumentException(name);
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"ODONTOLOGO", "HIGIENISTA", "RECEPCION", "FACTURACION", "PLATFORM_ADMIN"})
    void onlyClinicAdministratorsManagePractitioners(String role) {
        PractitionerDetails existing = asAdmin(() -> practitioners.create(external("Dra. Externa", "28", "1234")));

        for (String name : managing().toList()) {
            assertThatThrownBy(() -> as(ADMIN, List.of(role), () -> {
                        operation(name, existing.id()).run();
                        return null;
                    }))
                    .as(name)
                    .isInstanceOf(AccessDeniedException.class);
        }

        assertThat(asAdmin(() -> practitioners.get(existing.id()))).isEqualTo(existing);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN_CLINICA", "ODONTOLOGO", "HIGIENISTA", "RECEPCION", "FACTURACION"})
    void everyMemberOfTheClinicReadsThePractitioners(String role) {
        PractitionerDetails existing = asAdmin(() -> practitioners.create(external("Dra. Externa", "28", "1234")));

        assertThat(as(ADMIN, List.of(role), () -> practitioners.get(existing.id())))
                .isEqualTo(existing);
        assertThat(as(ADMIN, List.of(role), () -> practitioners.list(null, PageRequest.of(0, 20))))
                .containsExactly(existing);
        assertThat(as(ADMIN, List.of(role), () -> activePractitioners.list()))
                .extracting(PractitionerSummary::id)
                .containsExactly(existing.id());
    }

    /** Platform administrators have no clinic role: they see no clinic's practitioners. */
    @Test
    void platformAdministratorsReadNoPractitioners() {
        PractitionerDetails existing = asAdmin(() -> practitioners.create(external("Dra. Externa", "28", "1234")));

        for (String name : List.of("list", "get", "active")) {
            assertThatThrownBy(() -> as(ADMIN, List.of("PLATFORM_ADMIN"), () -> {
                        operation(name, existing.id()).run();
                        return null;
                    }))
                    .as(name)
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @ParameterizedTest
    @MethodSource("managing")
    void withoutAUserNothingIsManaged(String name) {
        PractitionerDetails existing = asAdmin(() -> practitioners.create(external("Dra. Externa", "28", "1234")));

        assertThatThrownBy(() -> TenantContext.runAs(CLINIC_A, operation(name, existing.id())))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    // --- Creating and changing ---

    @Test
    void anAdministratorCreatesARecordWithoutAUserAndItIsAudited() throws SQLException {
        PractitionerDetails created = asAdmin(() -> practitioners.create(external("Dra. Externa", "28", "1234")));

        assertThat(created.userId()).isNull();
        assertThat(created.displayName()).isEqualTo("Dra. Externa");
        assertThat(created.specialty()).isEqualTo(PractitionerSpecialty.ORTHODONTICS);
        assertThat(created.licenseProvince()).isEqualTo("28");
        assertThat(created.licenseNumber()).isEqualTo("1234");
        assertThat(created.agendaColor()).isEqualTo("#1E88E5");
        assertThat(created.status()).isEqualTo(PractitionerRoleStatus.ACTIVE);
        assertThat(created.version()).isZero();
        assertThat(AuditLogDatabase.actions(CLINIC_A, created.id())).containsExactly("CREATE");
    }

    @Test
    void aDentistAndAHygienistAreLinkedToTheirUsers() throws SQLException {
        UUID dentist = member(CLINIC_A, ClinicRole.ODONTOLOGO);
        UUID hygienist = member(CLINIC_A, ClinicRole.HIGIENISTA);

        PractitionerDetails forDentist =
                asAdmin(() -> practitioners.create(linked(dentist, PractitionerSpecialty.ENDODONTICS, "08", "2001")));
        PractitionerDetails forHygienist = asAdmin(
                () -> practitioners.create(linked(hygienist, PractitionerSpecialty.DENTAL_HYGIENE, null, null)));

        assertThat(forDentist.userId()).isEqualTo(dentist);
        assertThat(forHygienist.userId()).isEqualTo(hygienist);
        assertThat(forHygienist.licenseNumber()).isNull();
    }

    /** A record can be prepared while the dentist has not accepted the invitation yet. */
    @Test
    void aPendingInvitationMayBeLinked() throws SQLException {
        UUID invited = UUID.randomUUID();
        createdUsers.add(invited);
        IdentityTestDatabase.insertUser(invited);
        IdentityTestDatabase.insertInvitation(CLINIC_A, invited, ClinicRole.ODONTOLOGO);

        PractitionerDetails created = asAdmin(
                () -> practitioners.create(linked(invited, PractitionerSpecialty.GENERAL_DENTISTRY, "28", "3001")));

        assertThat(created.userId()).isEqualTo(invited);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN_CLINICA", "RECEPCION", "FACTURACION"})
    void onlyDentistsAndHygienistsMayBeLinked(String role) throws SQLException {
        UUID member = member(CLINIC_A, ClinicRole.valueOf(role));

        assertConflict(
                () -> asAdmin(() ->
                        practitioners.create(linked(member, PractitionerSpecialty.GENERAL_DENTISTRY, "28", "3002"))),
                Reason.USER_NOT_ELIGIBLE);
        assertThat(asAdmin(() -> practitioners.list(null, PageRequest.of(0, 20))))
                .isEmpty();
    }

    @Test
    void inactiveMembersAndMembersOfAnotherClinicMayNotBeLinked() throws SQLException {
        UUID gone = member(CLINIC_A, ClinicRole.ODONTOLOGO);
        IdentityTestDatabase.setMembershipActive(CLINIC_A, gone, false);
        UUID elsewhere = member(CLINIC_B, ClinicRole.ODONTOLOGO);
        UUID nobody = UUID.randomUUID();

        for (UUID user : List.of(gone, elsewhere, nobody)) {
            assertConflict(
                    () -> asAdmin(() ->
                            practitioners.create(linked(user, PractitionerSpecialty.GENERAL_DENTISTRY, "28", "3003"))),
                    Reason.USER_NOT_ELIGIBLE);
        }
    }

    @Test
    void theClinicalAreaSuitsTheRole() throws SQLException {
        UUID dentist = member(CLINIC_A, ClinicRole.ODONTOLOGO);
        UUID hygienist = member(CLINIC_A, ClinicRole.HIGIENISTA);

        assertConflict(
                () -> asAdmin(
                        () -> practitioners.create(linked(dentist, PractitionerSpecialty.DENTAL_HYGIENE, null, null))),
                Reason.SPECIALTY_MISMATCH);
        assertConflict(
                () -> asAdmin(() ->
                        practitioners.create(linked(hygienist, PractitionerSpecialty.PERIODONTICS, "28", "3004"))),
                Reason.SPECIALTY_MISMATCH);
    }

    @Test
    void aUserHasOneRecordPerClinic() throws SQLException {
        UUID dentist = member(CLINIC_A, ClinicRole.ODONTOLOGO);
        PractitionerDetails first =
                asAdmin(() -> practitioners.create(linked(dentist, PractitionerSpecialty.ORTHODONTICS, "28", "3005")));
        asAdmin(() -> practitioners.deactivate(first.id(), first.version()));

        assertConflict(
                () -> asAdmin(
                        () -> practitioners.create(linked(dentist, PractitionerSpecialty.ORTHODONTICS, "28", "3006"))),
                Reason.USER_TAKEN);
    }

    @Test
    void activeRecordsNeverShareALicenseNumberOfTheSameProvince() {
        PractitionerDetails first = asAdmin(() -> practitioners.create(external("Dra. Uno", "28", "1234")));

        assertConflict(
                () -> asAdmin(() -> practitioners.create(external("Dra. Dos", "28", "1234"))), Reason.LICENSE_TAKEN);
        // Another province's college may give the same number.
        PractitionerDetails otherProvince = asAdmin(() -> practitioners.create(external("Dra. Tres", "08", "1234")));
        assertConflict(
                () -> asAdmin(() -> practitioners.update(
                        otherProvince.id(), external("Dra. Tres", "28", "1234"), otherProvince.version())),
                Reason.LICENSE_TAKEN);

        // An inactive record frees its number, until it is activated again.
        PractitionerDetails inactive = asAdmin(() -> practitioners.deactivate(first.id(), first.version()));
        PractitionerDetails replacement = asAdmin(() -> practitioners.create(external("Dra. Dos", "28", "1234")));
        assertThat(replacement.status()).isEqualTo(PractitionerRoleStatus.ACTIVE);
        assertConflict(
                () -> asAdmin(() -> practitioners.activate(inactive.id(), inactive.version())), Reason.LICENSE_TAKEN);
    }

    @Test
    void anAdministratorReplacesTheDataOfARecord() throws SQLException {
        PractitionerDetails created = asAdmin(() -> practitioners.create(external("Dra. Externa", "28", "1234")));
        PractitionerData changed = new PractitionerData(
                null, "Dra. Externa García", PractitionerSpecialty.PERIODONTICS, "41", "41-77", "#00897B");

        PractitionerDetails updated = asAdmin(() -> practitioners.update(created.id(), changed, created.version()));

        assertThat(updated.displayName()).isEqualTo("Dra. Externa García");
        assertThat(updated.specialty()).isEqualTo(PractitionerSpecialty.PERIODONTICS);
        assertThat(updated.licenseProvince()).isEqualTo("41");
        assertThat(updated.licenseNumber()).isEqualTo("41-77");
        assertThat(updated.agendaColor()).isEqualTo("#00897B");
        assertThat(updated.version()).isEqualTo(created.version() + 1);
        assertThat(AuditLogDatabase.actions(CLINIC_A, created.id())).containsExactly("CREATE", "UPDATE");
    }

    /** Someone who had no account gets one: their record is linked to it, once. */
    @Test
    void aRecordWithoutAUserIsLinkedOnceAndNeverChangesUser() throws SQLException {
        UUID dentist = member(CLINIC_A, ClinicRole.ODONTOLOGO);
        UUID otherDentist = member(CLINIC_A, ClinicRole.ODONTOLOGO);
        PractitionerDetails created = asAdmin(() -> practitioners.create(external("Dr. Colaborador", "28", "1234")));

        PractitionerDetails linkedRecord = asAdmin(() -> practitioners.update(
                created.id(), linked(dentist, PractitionerSpecialty.ORTHODONTICS, "28", "1234"), created.version()));

        assertThat(linkedRecord.userId()).isEqualTo(dentist);
        assertConflict(
                () -> asAdmin(() -> practitioners.update(
                        created.id(),
                        linked(otherDentist, PractitionerSpecialty.ORTHODONTICS, "28", "1234"),
                        linkedRecord.version())),
                Reason.USER_FIXED);
        assertConflict(
                () -> asAdmin(() -> practitioners.update(
                        created.id(), external("Dr. Colaborador", "28", "1234"), linkedRecord.version())),
                Reason.USER_FIXED);
        assertThat(asAdmin(() -> practitioners.get(created.id())).userId()).isEqualTo(dentist);
    }

    @Test
    void aChangeMadeOnAnOldVersionIsAConflict() {
        PractitionerDetails created = asAdmin(() -> practitioners.create(external("Dra. Externa", "28", "1234")));
        asAdmin(() -> practitioners.update(created.id(), external("Dra. Cambiada", "28", "1234"), created.version()));

        assertConflict(
                () -> asAdmin(() ->
                        practitioners.update(created.id(), external("Dra. Otra", "28", "1234"), created.version())),
                Reason.VERSION);
        assertConflict(() -> asAdmin(() -> practitioners.deactivate(created.id(), created.version())), Reason.VERSION);
    }

    @Test
    void anotherClinicsRecordIsNotFound() {
        PractitionerDetails inB = as(
                ADMIN,
                List.of("ADMIN_CLINICA"),
                CLINIC_B,
                () -> practitioners.create(external("Dra. B", "28", "1234")));

        assertThatThrownBy(() -> asAdmin(() -> practitioners.get(inB.id())))
                .isInstanceOf(PractitionerNotFoundException.class);
        assertThatThrownBy(() -> asAdmin(() -> practitioners.update(inB.id(), external("Dra. A", "28", "9999"), 0)))
                .isInstanceOf(PractitionerNotFoundException.class);
        assertThatThrownBy(() -> asAdmin(() -> practitioners.deactivate(inB.id(), 0)))
                .isInstanceOf(PractitionerNotFoundException.class);
        assertThat(as(ADMIN, List.of("ADMIN_CLINICA"), CLINIC_B, () -> practitioners.get(inB.id())))
                .isEqualTo(inB);
    }

    // --- Following the membership ---

    @Test
    void deactivatingTheMembershipDeactivatesTheRecord() throws SQLException {
        UUID dentist = member(CLINIC_A, ClinicRole.ODONTOLOGO);
        PractitionerDetails record =
                asAdmin(() -> practitioners.create(linked(dentist, PractitionerSpecialty.ORTHODONTICS, "28", "1234")));
        ClinicUserDetails membership = detailsOf(dentist);

        asAdmin(() -> users.deactivate(membership.id(), membership.version()));

        assertThat(asAdmin(() -> practitioners.get(record.id())).status()).isEqualTo(PractitionerRoleStatus.INACTIVE);
        assertThat(AuditLogDatabase.actions(CLINIC_A, record.id())).containsExactly("CREATE", "UPDATE");
    }

    @Test
    void aRoleThatNoLongerSuitsTheRecordDeactivatesIt() throws SQLException {
        UUID toReception = member(CLINIC_A, ClinicRole.ODONTOLOGO);
        UUID toHygiene = member(CLINIC_A, ClinicRole.ODONTOLOGO);
        PractitionerDetails first = asAdmin(
                () -> practitioners.create(linked(toReception, PractitionerSpecialty.ORTHODONTICS, "28", "1234")));
        PractitionerDetails second = asAdmin(
                () -> practitioners.create(linked(toHygiene, PractitionerSpecialty.ORTHODONTICS, "28", "5678")));

        ClinicUserDetails reception = detailsOf(toReception);
        asAdmin(() -> users.changeRole(reception.id(), ClinicRole.RECEPCION, reception.version()));
        ClinicUserDetails hygiene = detailsOf(toHygiene);
        asAdmin(() -> users.changeRole(hygiene.id(), ClinicRole.HIGIENISTA, hygiene.version()));

        assertThat(asAdmin(() -> practitioners.get(first.id())).status()).isEqualTo(PractitionerRoleStatus.INACTIVE);
        assertThat(asAdmin(() -> practitioners.get(second.id())).status()).isEqualTo(PractitionerRoleStatus.INACTIVE);
    }

    /** An administrator is not a practitioner; coming back as a dentist does not reactivate the record by itself. */
    @Test
    void aRoleThatIsNotClinicalDeactivatesTheRecordUntilAnAdministratorActivatesIt() throws SQLException {
        UUID dentist = member(CLINIC_A, ClinicRole.ODONTOLOGO);
        PractitionerDetails record =
                asAdmin(() -> practitioners.create(linked(dentist, PractitionerSpecialty.ORTHODONTICS, "28", "1234")));
        ClinicUserDetails asDentist = detailsOf(dentist);
        asAdmin(() -> users.changeRole(asDentist.id(), ClinicRole.ADMIN_CLINICA, asDentist.version()));
        ClinicUserDetails asAdministrator = detailsOf(dentist);

        asAdmin(() -> users.changeRole(asAdministrator.id(), ClinicRole.ODONTOLOGO, asAdministrator.version()));

        PractitionerDetails inactive = asAdmin(() -> practitioners.get(record.id()));
        assertThat(inactive.status()).isEqualTo(PractitionerRoleStatus.INACTIVE);
        assertThat(asAdmin(() -> practitioners.activate(inactive.id(), inactive.version()))
                        .status())
                .isEqualTo(PractitionerRoleStatus.ACTIVE);
    }

    @Test
    void aRecordIsActivatedOnlyWhileItsUserIsEligible() throws SQLException {
        UUID dentist = member(CLINIC_A, ClinicRole.ODONTOLOGO);
        PractitionerDetails record =
                asAdmin(() -> practitioners.create(linked(dentist, PractitionerSpecialty.ORTHODONTICS, "28", "1234")));
        ClinicUserDetails membership = detailsOf(dentist);
        asAdmin(() -> users.deactivate(membership.id(), membership.version()));
        PractitionerDetails inactive = asAdmin(() -> practitioners.get(record.id()));

        assertConflict(
                () -> asAdmin(() -> practitioners.activate(inactive.id(), inactive.version())),
                Reason.USER_NOT_ELIGIBLE);

        // Reactivating the membership does not reactivate the record by itself; the administrator does.
        IdentityTestDatabase.setMembershipActive(CLINIC_A, dentist, true);
        assertThat(asAdmin(() -> practitioners.get(record.id())).status()).isEqualTo(PractitionerRoleStatus.INACTIVE);
        assertThat(asAdmin(() -> practitioners.activate(inactive.id(), inactive.version()))
                        .status())
                .isEqualTo(PractitionerRoleStatus.ACTIVE);
    }

    // --- For new appointments ---

    @Test
    void aDeactivatedPractitionerIsNotOfferedForNewAppointments() {
        PractitionerDetails active = asAdmin(() -> practitioners.create(external("Dra. Activa", "28", "1111")));
        PractitionerDetails created = asAdmin(() -> practitioners.create(external("Dr. Inactivo", "28", "2222")));
        PractitionerDetails inactive = asAdmin(() -> practitioners.deactivate(created.id(), created.version()));

        assertThat(asReception(() -> activePractitioners.list()))
                .containsExactly(new PractitionerSummary(
                        active.id(), "Dra. Activa", PractitionerSpecialty.ORTHODONTICS, "#1E88E5", true));
        assertThat(asReception(() -> activePractitioners.requireActive(active.id()))
                        .id())
                .isEqualTo(active.id());
        assertThatThrownBy(() -> asReception(() -> activePractitioners.requireActive(inactive.id())))
                .isInstanceOf(PractitionerUnavailableException.class);
    }

    /** What was given with a practitioner keeps showing them after they are deactivated. */
    @Test
    void pastAppointmentsStillDescribeADeactivatedPractitioner() {
        PractitionerDetails created = asAdmin(() -> practitioners.create(external("Dr. Inactivo", "28", "2222")));
        asAdmin(() -> practitioners.deactivate(created.id(), created.version()));
        PractitionerDetails inB = as(
                ADMIN,
                List.of("ADMIN_CLINICA"),
                CLINIC_B,
                () -> practitioners.create(external("Dra. B", "28", "3333")));

        Map<UUID, PractitionerSummary> described =
                asReception(() -> activePractitioners.describe(List.of(created.id(), inB.id(), UUID.randomUUID())));

        assertThat(described).containsOnlyKeys(created.id());
        assertThat(described.get(created.id()).displayName()).isEqualTo("Dr. Inactivo");
        assertThat(described.get(created.id()).active()).isFalse();
        assertThat(asReception(() -> activePractitioners.describe(List.of()))).isEmpty();
        assertThatThrownBy(() -> asReception(() -> activePractitioners.requireActive(inB.id())))
                .isInstanceOf(PractitionerUnavailableException.class);
    }

    // --- Helpers ---

    private static <T> T asReception(Supplier<T> operation) {
        return as(ADMIN, List.of("RECEPCION"), operation);
    }

    private static PractitionerData external(String name, String province, String number) {
        return new PractitionerData(null, name, PractitionerSpecialty.ORTHODONTICS, province, number, "#1E88E5");
    }

    private static PractitionerData linked(
            UUID user, PractitionerSpecialty specialty, @Nullable String province, @Nullable String number) {
        return new PractitionerData(user, "Profesional Pruebas", specialty, province, number, "#43A047");
    }

    private static void assertConflict(Runnable operation, Reason reason) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        PractitionerConflictException.class,
                        conflict -> assertThat(conflict.reason()).isEqualTo(reason));
    }
}
