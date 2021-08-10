/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.kohsuke.args4j.Argument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.TabbedLineReader;
import org.theseed.samples.SampleId;
import org.theseed.utils.BaseProcessor;
import org.theseed.utils.ParseFailureException;

/**
 * This method reads a predictions file containing both predicted and expected threonine data and a prediction file of virtual
 * samples containing only sample IDs and predictions.  The expected data will be added to samples for which it is available.
 * This cannot be done with a text-based join, since multiple sample IDs can represent the same sample.
 * The positional parameters are the name of the virtual-sample prediction file and the name of the real-sample prediction file.
 * The output will be to the standard output.
 *
 * The command-line options are as follows:
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 *
 * @author Bruce Parrello
 *
 */
public class MergePredictionsProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(MergePredictionsProcessor.class);
    /** hash of real sample IDs to production and density (in string form with a tab delimiter */
    private Map<SampleId, String> realMap;

    // COMMAND-LINE OPTIONS

    /** input virtual-sample file */
    @Argument(index = 0, metaVar = "virtual.predictions.tbl", usage = "file of predictions for virtual samples")
    private File virtualFile;

    /** input real-sample file */
    @Argument(index = 1, metaVar = "real.sample.tbl", usage = "file of density and production values for real samples")
    private File realFile;

    @Override
    protected void setDefaults() {
    }

    @Override
    protected boolean validateParms() throws IOException, ParseFailureException {
        if (! this.virtualFile.canRead())
            throw new FileNotFoundException("Virtual-sample file " + this.virtualFile + " is not found or unreadable.");
        if (! this.realFile.canRead())
            throw new FileNotFoundException("Real-sample file " + this.realFile + " is not found or unreadable.");
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Read the real-sample file into a map.
        this.realMap = new HashMap<SampleId, String>(4000);
        try (TabbedLineReader realStream = new TabbedLineReader(this.realFile)) {
            int sampleCol = realStream.findField("sample_id");
            int densityCol = realStream.findField("density");
            int prodCol = realStream.findField("production");
            for (TabbedLineReader.Line line : realStream) {
                SampleId sample = new SampleId(line.get(sampleCol));
                String density = line.get(densityCol);
                String prod = line.get(prodCol);
                this.realMap.put(sample, prod + "\t" + density);
            }
        }
        // Start the output.  There is one line of output for each line of virtual input.
        try (TabbedLineReader virtStream = new TabbedLineReader(this.virtualFile)) {
            System.out.println("sample_id\tpredicted\tproduction\tdensity");
            int sampleCol = virtStream.findField("sample_id");
            int predCol = virtStream.findField("predicted");
            for (TabbedLineReader.Line line : virtStream) {
                // Get this sample ID.
                SampleId sample = new SampleId(line.get(sampleCol));
                // Default to blank production\density columns.
                String realData = this.realMap.getOrDefault(sample, "\t");
                // Write the output line.
                System.out.format("%s\t%s\t%s%n", sample.toString(), line.get(predCol), realData);
            }
        }
    }
}
