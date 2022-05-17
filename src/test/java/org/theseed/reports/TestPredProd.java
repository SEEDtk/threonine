/**
 *
 */
package org.theseed.reports;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.theseed.io.TabbedLineReader;

/**
 * Test prediction analysis, which is a key component in ROC analysis.
 *
 * @author Bruce Parrello
 *
 */
class TestPredProd {

    @Test
    void testAnalyzer() throws IOException {
        PredictionAnalyzer analyzer = new PredictionAnalyzer();
        try (var inStream = new TabbedLineReader(new File("data", "predprod.tbl"))) {
            for (TabbedLineReader.Line line : inStream)
                analyzer.add(line.getDouble(0), line.getDouble(1));
        }
        assertThat(analyzer.size(), equalTo(22));
        var matrix = analyzer.getMatrix(0.5);
        assertThat(matrix.getCutoff(), equalTo(0.5));
        assertThat(matrix.trueNegativeRatio(), closeTo(0.3182, 0.0001));
        assertThat(matrix.trueNegativeCount(), equalTo(7));
        assertThat(matrix.truePositiveRatio(), closeTo(0.1818, 0.0001));
        assertThat(matrix.truePositiveCount(), equalTo(4));
        assertThat(matrix.falseNegativeRatio(), closeTo(0.2727, 0.0001));
        assertThat(matrix.falseNegativeCount(), equalTo(6));
        assertThat(matrix.falsePositiveRatio(), closeTo(0.2273, 0.0001));
        assertThat(matrix.falsePositiveCount(), equalTo(5));
        assertThat(matrix.accuracy(), closeTo(0.5, 0.0001));
        assertThat(matrix.fallout(), closeTo(0.4167, 0.0001));
        assertThat(matrix.missRatio(), closeTo(0.6, 0.0001));
        assertThat(matrix.sensitivity(), closeTo(0.4, 0.0001));
        matrix.recompute(0.6);
        assertThat(matrix.getCutoff(), equalTo(0.6));
        assertThat(matrix.accuracy(), closeTo(0.6818, 0.0001));
        assertThat(matrix.missRatio(), closeTo(0.5555, 0.0001));
        assertThat(matrix.truePositiveCount(), equalTo(4));
        assertThat(matrix.falsePositiveCount(), equalTo(2));
        assertThat(matrix.trueNegativeCount(), equalTo(11));
        double[] levels = analyzer.getAllPredictions();
        assertThat(levels.length, equalTo(9));
        assertThat(levels[0], equalTo(0.9));
        assertThat(levels[1], equalTo(0.8));
        assertThat(levels[2], equalTo(0.7));
        assertThat(levels[3], equalTo(0.6));
        assertThat(levels[4], equalTo(0.5));
        assertThat(levels[5], equalTo(0.4));
        assertThat(levels[6], equalTo(0.3));
        assertThat(levels[7], equalTo(0.2));
        assertThat(levels[8], equalTo(0.1));
    }

}
