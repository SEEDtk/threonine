/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Iterator;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.reports.ThrSampleFormatter;
import org.theseed.utils.BaseProcessor;

/**
 * This program reads the choices.tbl file produced by the "thrfix" command and produces
 * a machine learning prediction file for all the possible combinations.  Currently, only
 * time-point = 24 is output.
 *
 * The positional parameters are the name of the choices file and the name of the output file.
 *
 * The command-line options are as follows.
 *
 * -h 	display command-line usage
 * -v	display more frequent log messages
 *
 * --delim 	type of delimiter to use (COMMA or TAB)
 *
 * @author Bruce Parrello
 *
 */
public class ThrallProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(ThrallProcessor.class);
    /** sample ID formatter */
    private ThrSampleFormatter formatter;
    /** delimiter string */
    private String delim;

    /**
     * Enumerator for delimiters
     */
    private static enum Delim {
        COMMA(","), TAB("\t");

        private String value;

        private Delim(String val) {
            this.value = val;
        }

        public String getValue() {
            return this.value;
        }
    }

    // COMMAND-LINE

    /** delimiter for output */
    @Option(name = "--delim", usage = "delimiter for output")
    private Delim delimiter;

    /** choices file */
    @Argument(index = 0, metaVar = "choices.tbl", usage = "choice file for strain IDs", required = true)
    private File choiceFile;

    /** output file */
    @Argument(index = 1, metaVar = "output.tbl", usage = "output file for prediction test", required = true)
    private File outFile;

    @Override
    protected void setDefaults() {
        this.delimiter = Delim.TAB;
    }

    @Override
    protected boolean validateParms() throws IOException {
        // Verify the input file.
        if (! this.choiceFile.canRead())
            throw new FileNotFoundException("Choices file " + this.choiceFile + " is not found or unreadable.");
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Get the delimiter string.
        this.delim = this.delimiter.getValue();
        // Initialize the formatter.
        log.info("Initializing formatting data.");
        this.formatter = new ThrSampleFormatter();
        this.formatter.setupChoices(this.choiceFile);
        try (PrintWriter writer = new PrintWriter(this.outFile)) {
            // Write the output header.
            writer.println("sample_id" + this.delim +
                    StringUtils.join(this.formatter.getTitles(), this.delim));
            // Iterate through the sample IDs.
            log.info("Computing sample IDs.");
            int processed = 0;
            Iterator<String> iter = this.formatter.new SampleIterator();
            while (iter.hasNext()) {
                String sampleId = iter.next();
                double[] parms = this.formatter.parseSample(sampleId);
                writer.println(sampleId + this.delim +
                        Arrays.stream(parms).mapToObj(x -> Double.toString(x)).collect(Collectors.joining(this.delim)));
                processed++;
                if (log.isInfoEnabled() && processed % 5000 == 0)
                    log.info("{} samples processed.", processed);
            }
            log.info("Processing complete. {} rows generated.", processed);
        }
    }

}
