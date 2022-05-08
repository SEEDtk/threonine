/**
 *
 */
package org.theseed.experiments;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;

import org.junit.jupiter.api.Test;

/**
 * Test the sample name pattern.
 *
 * @author Bruce Parrello
 *
 */
class SampleNameTest {

    @Test
    void test() {
        Matcher m = MultiExperimentGroup.SAMPLE_NAME.matcher("RNAseq 12h A2");
        assertThat(m.matches(), equalTo(true));
        assertThat(m.group(1), equalTo("RNAseq"));
        assertThat(m.group(2), equalTo("12"));
        assertThat(m.group(3), equalTo("A2"));
        m = MultiExperimentGroup.SAMPLE_NAME.matcher("1.2 A-D 24h B13");
        assertThat(m.matches(), equalTo(true));
        assertThat(m.group(1), equalTo("1.2 A-D"));
        assertThat(m.group(2), equalTo("24"));
        assertThat(m.group(3), equalTo("B13"));
        m = MultiExperimentGroup.SAMPLE_NAME.matcher("02.3 C10");
        assertThat(m.matches(), equalTo(true));
        assertThat(m.group(1), equalTo("2.3"));
        assertThat(m.group(2), nullValue());
        assertThat(m.group(3), equalTo("C10"));
        m = MultiExperimentGroup.SAMPLE_NAME.matcher("no plas 9h D8");
        assertThat(m.matches(), equalTo(true));
        assertThat(m.group(1), equalTo("no plas"));
        assertThat(m.group(2), equalTo("9"));
        assertThat(m.group(3), equalTo("D8"));
    }

    @Test
    void testSheetParse() throws IOException {
        File testDir = new File("data", "S3");
        var testGroup = new MultiExperimentGroup(testDir, "test");
        var desc = testGroup.parseSampleName("no plasmid 1 A4");
        assertThat(desc.getPlate(), equalTo("NONE1"));
        assertThat(desc.getWell(), equalTo("A4"));
        assertThat(desc.getTime(), equalTo(24.0));
        desc = testGroup.parseSampleName("no plasmid 2 D11");
        assertThat(desc.getPlate(), equalTo("NONE2"));
        assertThat(desc.getWell(), equalTo("D11"));
        assertThat(desc.getTime(), equalTo(24.0));
        desc = testGroup.parseSampleName("1.2.3 A-D C3");
        assertThat(desc.getPlate(), equalTo("1.2.3A-D"));
        assertThat(desc.getWell(), equalTo("C3"));
        assertThat(desc.getTime(), equalTo(24.0));
        desc = testGroup.parseSampleName("1.2.3A-D B10");
        assertThat(desc.getPlate(), equalTo("1.2.3A-D"));
        assertThat(desc.getWell(), equalTo("B10"));
        assertThat(desc.getTime(), equalTo(24.0));
        desc = testGroup.parseSampleName("S2.1 F4");
        assertThat(desc.getPlate(), equalTo("S2.1"));
        assertThat(desc.getWell(), equalTo("F4"));
        assertThat(desc.getTime(), equalTo(24.0));
    }

}
