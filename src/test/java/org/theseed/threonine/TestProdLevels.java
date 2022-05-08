/**
 *
 */
package org.theseed.threonine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.theseed.io.TabbedLineReader;

/**
 * @author Bruce Parrello
 *
 */
public class TestProdLevels {

    @Test
    public void test() throws IOException {
        assertThat(Production.getNames(), arrayContaining("None", "Low", "High"));
        assertThat(Production.getLevel(0.0), equalTo("None"));
        File levelFile = new File("data", "levels.tbl");
        try (TabbedLineReader inStream = new TabbedLineReader(levelFile)) {
            int prodCol = inStream.findField("production");
            int idCol = inStream.findField("sample_id");
            int levelCol = inStream.findField("prod_level");
            for (TabbedLineReader.Line line : inStream) {
                double prod = line.getDouble(prodCol);
                String level = line.get(levelCol);
                String id = line.get(idCol);
                assertThat(id, Production.getLevel(prod), equalTo(level));
            }
        }
    }

    @Test
    public void testGrowthData() {
        GrowthData testGrowth = new GrowthData("sample1", 24.0);
        testGrowth.merge(6.2152,  4.2400, "22Apr2.1.1", "E4");
        testGrowth.merge(7.4610,  5.0400, "22Apr2.1.1", "E4");
        testGrowth.merge(1.4500,  4.8600, "22Apr2.1.1", "E4");
        testGrowth.removeBadZeroes(1.2);
        boolean ok = testGrowth.removeOutlier(1.2);
        assertThat(ok, equalTo(true));
        assertThat(testGrowth.getOrigins(), equalTo("22Apr2.1.2A-D:E4, 22Apr2.1.1A-D:E4, (22Apr2.1.3A-D:E4)"));
        assertThat(testGrowth.getProduction(), closeTo(6.8381, 0.0001));
        assertThat(testGrowth.getDensity(), closeTo(9.2800, 0.0001));
    }

}
