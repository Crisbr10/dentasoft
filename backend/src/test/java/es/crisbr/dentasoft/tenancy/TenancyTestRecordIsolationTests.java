package es.crisbr.dentasoft.tenancy;

import es.crisbr.dentasoft.TestData;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;

/** The isolation template on the test-only table {@code tenancy_test_record}: the example every module follows. */
class TenancyTestRecordIsolationTests extends TenantIsolationTest<TenancyTestRecord> {

    @Autowired
    private TenancyTestRecordRepository records;

    @Override
    protected JpaRepository<TenancyTestRecord, UUID> repository() {
        return records;
    }

    @Override
    protected String table() {
        return "tenancy_test_record";
    }

    @Override
    protected TenancyTestRecord newEntity(int n) {
        return new TenancyTestRecord(TestData.person(n).givenName());
    }

    @Override
    protected void modify(TenancyTestRecord record) {
        record.setLabel(record.getLabel() + " (changed)");
    }
}
