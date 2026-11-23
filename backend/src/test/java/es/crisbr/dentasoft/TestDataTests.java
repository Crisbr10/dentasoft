package es.crisbr.dentasoft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TestDataTests {

    @Test
    void emailAddressesUseTheReservedTestDomain() {
        assertThat(IntStream.range(0, 50).mapToObj(n -> TestData.person(n).email()))
                .allMatch(email -> email.endsWith(".test"))
                .doesNotHaveDuplicates();
    }

    @Test
    void theSameNumberAlwaysReturnsTheSamePerson() {
        assertThat(TestData.person(7)).isEqualTo(TestData.person(7));
    }

    @Test
    void rejectsNegativeNumbers() {
        assertThatIllegalArgumentException().isThrownBy(() -> TestData.person(-1));
    }

    @Test
    void clinicsAreDifferentAndUseUuidVersion7() {
        assertThat(TestData.CLINIC_A.id()).isNotEqualTo(TestData.CLINIC_B.id());
        assertThat(TestData.CLINIC_A.id().version()).isEqualTo(7);
        assertThat(TestData.CLINIC_B.id().version()).isEqualTo(7);
    }
}
