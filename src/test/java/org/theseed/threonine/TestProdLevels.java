/**
 *
 */
package org.theseed.threonine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.io.File;
import java.io.IOException;

import org.junit.Test;
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

}
