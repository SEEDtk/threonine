/**
 *
 */
package org.theseed.threonine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.theseed.test.Matchers.*;

import java.io.File;
import java.io.IOException;
import static org.hamcrest.Matchers.*;

import org.junit.Test;
import org.theseed.io.TabbedLineReader;
import org.theseed.proteins.SampleId;

/**
 * @author Bruce Parrello
 *
 */
public class ThrTest {

    @Test
    public void growthTest() {
        GrowthData test = new GrowthData("name1", 4.5);
        test.merge(0.120259798, 2.09, "A1", "B1");
        test.merge(0.070732925, 1.85, "A2", "B2");
        test.merge(0.115688087, 1.8, "A3", "B3");
        test.merge(0.005205063, 2.05, "A4", "B4");
        assertThat(test.getOldStrain(), equalTo("name1"));
        assertThat(test.getProduction(), closeTo(0.07797146825, 0.0001));
        assertThat(test.getDensity(), closeTo(1.9475, 0.0001));
        assertThat(test.getNormalizedProduction(), closeTo(0.040646, 0.0001));
        assertThat(test.getProductionRate(), closeTo(0.01732699, 0.0001));
        assertThat(test.getOrigins(), equalTo("A1:B1, A2:B2, A3:B3, A4:B4"));
    }

    @Test
    public void idTest() {
        SampleId samp1 = new SampleId("7_D_TasdA_P_asdD_zwf_DasdDdapA_I_6_M1");
        assertThat(samp1.toStrain(), equalTo("7_D_TasdA_P_asdD_zwf_DasdDdapA"));
        assertThat(samp1.toString(), equalTo("7_D_TasdA_P_asdD_zwf_DasdDdapA_I_6_M1"));
        assertThat(samp1.getTimePoint(), equalTo(6.0));
        SampleId samp2 = new SampleId("7_D_TasdA_P_asdD_zwf_DasdDdapA_I_4p5_M1");
        assertThat(samp2.toStrain(), equalTo("7_D_TasdA_P_asdD_zwf_DasdDdapA"));
        assertThat(samp2.toString(), equalTo("7_D_TasdA_P_asdD_zwf_DasdDdapA_I_4p5_M1"));
        assertThat(samp2.getTimePoint(), equalTo(4.5));
        assertThat(samp2, lessThan(samp1));
        assertThat(samp2.getDeletes(), contains("asd", "dapA"));
        assertThat(samp2.isIPTG(), isTrue());
        samp2 = new SampleId("7_0_0_A_asdO_000_D000_0_12_M1");
        assertThat(samp2.getDeletes().size(), equalTo(0));
        assertThat(samp2.isIPTG(), isFalse());
    }

    @Test
    public void testSamplePattern() throws IOException {
        File testFile = new File("data", "idMap.tbl");
        try (TabbedLineReader testStream = new TabbedLineReader(testFile)) {
            for (TabbedLineReader.Line line : testStream) {
                String strain = line.get(1);
                boolean iptg = line.getFlag(3);
                double time = line.getDouble(4);
                String medium = line.get(5);
                SampleId sample = SampleId.translate(strain, time, iptg, medium);
                assertThat(strain, sample.toString(), equalTo(line.get(2)));
            }
        }
    }

}
