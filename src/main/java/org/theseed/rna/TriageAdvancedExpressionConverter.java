/**
 *
 */
package org.theseed.rna;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;

import org.theseed.io.TabbedLineReader;

/**
 * This class converts an expression value to -1, 0, or 1, depending on whether it is significantly smaller, close to, or significantly
 * higher than the baseline value.  The baseline values are read from a file.
 *
 * @author Bruce Parrello
 */
public class TriageAdvancedExpressionConverter extends AdvancedExpressionConverter {

    // FIELDS
    /** map of group IDs to baseline values */
    private Map<String, Double> baselineMap;
    /** name of the baseline value file */
    private File baseFile;
    /** maximum ratio for a -1 value */
    private static final double LOW_RATIO = 0.5;
    /** minimum ratio for a 1 value */
    private static final double HIGH_RATIO = 2.0;

    public TriageAdvancedExpressionConverter(IParms processor) {
        super(processor);
    }

    @Override
    protected void initialize(IParms processor) throws IOException {
        // Get the baseline file and verify that we can read it.
        this.baseFile = processor.getBaseFile();
        if (! this.baseFile.canRead())
            throw new FileNotFoundException("Baseline file " + this.baseFile + " is not found or unreadable.");
    }

    @Override
    protected void defineColumns(SortedSet<String> colTitles, Map<String, Set<String>> filterMap) throws IOException {
        // Here we need to read in the baseline values and map them to their group IDs.  First, we create a map from
        // feature IDs to baseline values.
        Map<String, Double> fidMap = new HashMap<String, Double>(RnaSeqFeatureFilter.NUM_FEATURES);
        try (TabbedLineReader baseStream = new TabbedLineReader(this.baseFile)) {
            int fidIdx = baseStream.findField("fid");
            int valIdx = baseStream.findField("baseline");
            for (TabbedLineReader.Line line : baseStream)
                fidMap.put(line.get(fidIdx), line.getDouble(valIdx));
            log.info("{} baseline values read from {}.", fidMap.size(), this.baseFile);
        }
        // Now create the map of column titles to baselines.  We take the mean of the values found.
        this.baselineMap = new HashMap<String, Double>(colTitles.size());
        for (String colTitle : colTitles) {
            Set<String> fids = filterMap.get(colTitle);
            double total = 0.0;
            int n = 0;
            for (String fid : fids) {
                if (fidMap.containsKey(fid)) {
                    total += fidMap.get(fid);
                    n++;
                }
            }
            if (n > 1)
                total /= n;
            this.baselineMap.put(colTitle, total);
        }
        log.info("{} baseline values stored for column groups.", this.baselineMap.size());
    }

    @Override
    protected double convert(String colTitle, double value) {
        // Default to a value of 0.
        double retVal = 0.0;
        double baseLine = this.baselineMap.getOrDefault(colTitle, 0.0);
        // For a null or missing baseline, we return either 0 or 1.
        if (baseLine == 0.0)
            retVal = (value > 0.0 ? 1.0 : 0.0);
        else {
            // Here we have a real baseline, which is the overwhelming situation.  1 is double the base and -1 is half.
            // Otherwise we leave it 0.
            double ratio = value / baseLine;
            if (ratio >= HIGH_RATIO)
                retVal = 1.0;
            else if (ratio <= LOW_RATIO)
                retVal = -1.0;
        }
        return retVal;
    }

}
