package org.theseed.threonine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import org.junit.Test;
import org.theseed.threonine.ExperimentData.Key;

public class TestExperiment {

    @Test
    public void test() throws IOException {
        ExperimentData exp = new ExperimentData("A2.1");
        // Read the layout file.
        File keyFile = new File("data", "E1.1/key.txt");
        exp.readKeyFile(keyFile);
        assertThat(exp.getStrain("A1"), equalTo("926DthrABCDasdDlysA rhtA pwt2.1.1"));
        assertThat(exp.getStrain("E7"), equalTo("926DthrABCDasdDtdhDmetL rhtA pwt2.1.1 +IPTG"));
        assertThat(exp.getStrain("C12"), nullValue());
        assertThat(exp.getStrain("H8"), nullValue());
        assertThat(exp.getStrain("H3"), equalTo("277DthrABCDasdDmetL pfb6.4.3 rhtA +IPTG"));
        // Read the 24-hour growth file.
        File growthFile = new File("data", "E1.1/set E1 24 hrs 072420.csv");
        exp.readGrowthFile(growthFile, 24.0);
        assertThat(exp.getResult("H8", 24.0), nullValue());
        assertThat(exp.getResult("E10", 24.0).getGrowth(), closeTo(0.204, 0.0005));
        assertThat(exp.getResult("E10", 12.0), nullValue());
        assertThat(exp.getResult("B8", 24.0).getGrowth(), closeTo(0.155, 0.0005));
        assertThat(exp.getResult("C2", 24.0).getGrowth(), closeTo(0.508, 0.0005));
        assertThat(exp.getResult("B9", 24.0).getGrowth(), equalTo(0.0));
        growthFile = new File("data", "E1.1/set E1 4.5 hrs 072320.csv");
        exp.readGrowthFile(growthFile, 4.5);
        assertThat(exp.getResult("H8", 24.0), nullValue());
        assertThat(exp.getResult("E10", 24.0).getGrowth(), closeTo(0.204, 0.0005));
        assertThat(exp.getResult("E10", 12.0), nullValue());
        assertThat(exp.getResult("B8", 24.0).getGrowth(), closeTo(0.155, 0.0005));
        assertThat(exp.getResult("C2", 24.0).getGrowth(), closeTo(0.508, 0.0005));
        assertThat(exp.getResult("B9", 24.0).getGrowth(), equalTo(0.0));
        assertThat(exp.getResult("B9", 4.5).getGrowth(), closeTo(0.030, 0.0005));
        growthFile = new File("data", "E1.1/set E1 5.5 hrs 072320.csv");
        exp.readGrowthFile(growthFile, 5.5);
        assertThat(exp.getResult("B9", 24.0).getGrowth(), equalTo(0.0));
        assertThat(exp.getResult("B9", 4.5).getGrowth(), closeTo(0.030, 0.0005));
        assertThat(exp.getResult("B9", 5.5).getGrowth(), closeTo(0.038, 0.0005));
        exp.removeBadWells();
        assertThat(exp.getResult("H8", 24.0), nullValue());
        assertThat(exp.getResult("E10", 24.0).getGrowth(), closeTo(0.204, 0.0005));
        assertThat(exp.getResult("E10", 12.0), nullValue());
        assertThat(exp.getResult("B8", 24.0).getGrowth(), closeTo(0.155, 0.0005));
        assertThat(exp.getResult("C2", 24.0).getGrowth(), closeTo(0.508, 0.0005));
        assertThat(exp.getResult("B9", 24.0), nullValue());
        assertThat(exp.getResult("B9", 4.5), nullValue());
        assertThat(exp.getResult("B9", 5.5), nullValue());
        File prodFile = new File ("data", "E1.1/prod.txt");
        exp.readProdFile(prodFile);
        assertThat(exp.getResult("H1", 5.5).getProduction(), closeTo(0.019, 0.0005));
        assertThat(exp.getResult("F11", 24.0).getProduction(), closeTo(1.304, 0.0005));
    }

    @Test
    public void testKeyString() {
        Key key = Key.create("  4.5h A1");
        assertThat(key.getTimePoint(), equalTo(4.5));
        assertThat(key.getWell(), equalTo("A1"));
        key = Key.create("");
        assertThat(key, nullValue());
        key = Key.create("24h H16  ");
        assertThat(key.getTimePoint(), equalTo(24.0));
        assertThat(key.getWell(), equalTo("H16"));
    }

    @Test
    public void testGrowthFiles() {
        File expDir = new File("data", "E1.1");
        Map<Double, File> growthFiles = ExperimentData.getGrowthFiles(expDir);
        assertThat(growthFiles.size(), equalTo(3));
        assertThat(growthFiles.get(4.5), equalTo(new File(expDir, "set E1 4.5 hrs 072320.csv")));
        assertThat(growthFiles.get(5.5), equalTo(new File(expDir, "set E1 5.5 hrs 072320.csv")));
        assertThat(growthFiles.get(24.0), equalTo(new File(expDir, "set E1 24 hrs 072420.csv")));
    }
}
