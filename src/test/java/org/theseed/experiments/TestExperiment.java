package org.theseed.experiments;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.theseed.test.Matchers.*;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

public class TestExperiment {

    @Test
    public void testKeyString() {
        ExperimentData.Key key = ExperimentData.Key.create("  4.5h A1");
        assertThat(key.getTimePoint(), equalTo(4.5));
        assertThat(key.getWell(), equalTo("A1"));
        key = ExperimentData.Key.create("");
        assertThat(key, nullValue());
        key = ExperimentData.Key.create("24h H16  ");
        assertThat(key.getTimePoint(), equalTo(24.0));
        assertThat(key.getWell(), equalTo("H16"));
    }

    @Test
    public void testMultiGroup() throws IOException {
        File groupDir = new File("data", "S4");
        ExperimentGroup group = new MultiExperimentGroup(groupDir, "S4");
        group.processFiles();
        Set<String> plates = new TreeSet<String>();
        for (ExperimentData exp : group) {
            String plate = exp.getId();
            plates.add(plate);
            Set<String> wells = new HashSet<String>(100);
            for (ExperimentData.Result r : exp)
                wells.add(r.getWell());
            switch (plate) {
            case "S4A0" :
                assertThat(wells.size(), equalTo(96));
                break;
            case "S4D1" :
            case "S4D2" :
                assertThat(plate, wells.size(), equalTo(80));
                break;
            case "S4E1" :
            case "S4E2" :
                assertThat(plate, wells.size(), equalTo(76));
                break;
            default :
                throw new RuntimeException("Invalid plate ID " + plate);
            }
        }
        assertThat(plates, contains("S4A0", "S4D1", "S4D2", "S4E1", "S4E2"));
        ExperimentData.Result result = group.getResult("D1", "B4", 24);
        assertThat(result.getWell(), equalTo("B4"));
        assertThat(result.getTimePoint(), equalTo(24.0));
        assertThat(result.getStrain(), equalTo("926DthrABCDasdDdapA pfb6.4.2"));
        assertThat(result.isIptg(), isFalse());
        assertThat(result.getGrowth(), closeTo(5.91, 0.001));
        assertThat(result.getProduction(), closeTo(0.019, 0.001));
        assertThat(result.isSuspect(), isFalse());
        result = group.getResult("D1", "F4", 24);
        assertThat(result.getWell(), equalTo("F4"));
        assertThat(result.getTimePoint(), equalTo(24.0));
        assertThat(result.getStrain(), equalTo("926DthrABCDasdDdapA pfb6.4.2"));
        assertThat(result.isIptg(), isTrue());
        assertThat(result.getGrowth(), closeTo(2.95, 0.001));
        assertThat(result.getProduction(), closeTo(0.059, 0.001));
        assertThat(result.isSuspect(), isTrue());
        result = group.getResult("D1", "D6", 24);
        assertThat(result.getWell(), equalTo("D6"));
        assertThat(result.getTimePoint(), equalTo(24.0));
        assertThat(result.getStrain(), equalTo("277DthrABCDasdDmetL pfb6.4.3 pyc plasmid"));
        assertThat(result.isIptg(), isFalse());
        assertThat(result.getGrowth(), closeTo(0.05, 0.001));
        assertThat(result.getProduction(), closeTo(0.010, 0.001));
        assertThat(result.isSuspect(), isFalse());
        result = group.getResult("D1", "H6", 24);
        assertThat(result.getWell(), equalTo("H6"));
        assertThat(result.getTimePoint(), equalTo(24.0));
        assertThat(result.getStrain(), equalTo("277DthrABCDasdDmetL pfb6.4.3 pyc plasmid"));
        assertThat(result.isIptg(), isTrue());
        assertThat(result.getGrowth(), closeTo(0.06, 0.001));
        assertThat(result.getProduction(), closeTo(0.012, 0.001));
        assertThat(result.isSuspect(), isFalse());
        groupDir = new File("data", "S2");
        group = new SetExperimentGroup(groupDir, "S2");
        group.processFiles();
        plates = new TreeSet<String>();
        for (ExperimentData exp : group) {
            String plate = exp.getId();
            plates.add(plate);
            Set<String> wells = new HashSet<String>(100);
            for (ExperimentData.Result r : exp)
                wells.add(r.getWell());
            assertThat(plate, wells.size(), equalTo(84));
        }
        assertThat(plates, contains("S2NONE", "S2P1", "S2P2", "S2P3", "S2P4", "S2P5", "S2P6", "S2P7"));
        result = group.getResult("P6", "E2", 24);
        assertThat(result.getWell(), equalTo("E2"));
        assertThat(result.getTimePoint(), equalTo(24.0));
        assertThat(result.getStrain(), equalTo("277 DrhtA ptac-thrABC rhtA"));
        assertThat(result.isIptg(), isFalse());
        assertThat(result.getGrowth(), closeTo(6.67, 0.001));
        assertThat(result.getProduction(), closeTo(0.00, 0.001));
        assertThat(result.isSuspect(), isFalse());
        result = group.getResult("P6", "C8", 24);
        assertThat(result.getWell(), equalTo("C8"));
        assertThat(result.getTimePoint(), equalTo(24.0));
        assertThat(result.getStrain(), equalTo("926 DlysA ptac-thrABC  ptac-asd rhtA"));
        assertThat(result.isIptg(), isTrue());
        assertThat(result.getGrowth(), closeTo(5.21, 0.001));
        assertThat(result.getProduction(), closeTo(0.00, 0.001));
        assertThat(result.isSuspect(), isFalse());
    }

}
