/**
 *
 */
package org.theseed.experiments;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

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

}
