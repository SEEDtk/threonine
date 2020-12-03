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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.LineReader;
import org.theseed.proteins.SampleId;
import org.theseed.reports.ThrSampleFormatter;
import org.theseed.utils.BaseProcessor;

/**
 * This program reads the choices.tbl file produced by the "thrfix" command and produces
 * a machine learning prediction file for all the possible combinations.  Currently, only
 * time-point = 24 is output.
 *
 * The positional parameters are the name of the model directory, the name of the choices file,
 * and the name of the output file.
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
    /** set of headers from the training file */
    private Set<String> trainingHeaders;
    /** delimiter string */
    private String delim;
    /** flags indicating which sample fragments to keep */
    private boolean[] keep;

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

    /** model directory */
    @Argument(index = 0, metaVar = "modelDir", usage = "directory containing the target model", required = true)
    private File modelDir;

    /** choices file */
    @Argument(index = 1, metaVar = "choices.tbl", usage = "choice file for strain IDs", required = true)
    private File choiceFile;

    /** output file */
    @Argument(index = 2, metaVar = "output.tbl", usage = "output file for prediction test", required = true)
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
        // Verify the model directory.
        File trainingFile = new File(this.modelDir, "training.tbl");
        if (! trainingFile.canRead())
            throw new FileNotFoundException(this.modelDir + " does not contain a readable training file.");
        else try (LineReader tStream = new LineReader(trainingFile)) {
            // Get the training file header.
            String headers = tStream.next();
            // Determine the names of the headers we need.
            this.trainingHeaders = new TreeSet<String>(Arrays.asList(StringUtils.split(headers, '\t')));
        }
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
        // Compute the columns to keep.
        String[] titles = this.formatter.getTitles();
        this.keep = new boolean[titles.length];
        for (int i = 0; i < titles.length; i++)
            this.keep[i] = this.trainingHeaders.contains(titles[i]);
        // Start writing the output.
        try (PrintWriter writer = new PrintWriter(this.outFile)) {
            // Write the output header.
            writer.println("sample_id" + this.delim +
                    StringUtils.join(this.keepers(this.formatter.getTitles()), this.delim));
            // Iterate through the sample IDs.
            log.info("Computing sample IDs.");
            int processed = 0;
            Iterator<String> iter = this.formatter.new SampleIterator();
            while (iter.hasNext()) {
                String sampleId = iter.next();
                // Check the insert and delete possibilities to make sure they are supported.
                SampleId sample = new SampleId(sampleId);
                String insert = sample.getFragment(SampleId.INSERT_COL);
                Set<String> deletes = sample.getDeletes().stream().map(x -> "D" + x).collect(Collectors.toSet());
                if ((insert.contentEquals("000") || trainingHeaders.contains(insert)) && trainingHeaders.containsAll(deletes)) {
                    double[] parms = this.formatter.parseSample(sampleId);
                    String parmString = IntStream.range(0, parms.length).filter(i -> this.keep[i]).mapToObj(i -> Double.toString(parms[i]))
                            .collect(Collectors.joining(this.delim));
                    writer.println(sampleId + this.delim + parmString);
                    processed++;
                    if (log.isInfoEnabled() && processed % 5000 == 0)
                        log.info("{} samples processed.", processed);
                }
            }
            log.info("Processing complete. {} rows generated.", processed);
        }
    }

    /**
     * @return a list of the title strings we want to keep
     *
     * @param titles full list of title strings
     */
    private List<String> keepers(String[] titles) {
        List<String> retVal = IntStream.range(0, titles.length).filter(i -> this.keep[i]).mapToObj(i -> titles[i]).collect(Collectors.toList());
        return retVal;
    }

}
