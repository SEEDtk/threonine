/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.basic.ParseFailureException;
import org.theseed.io.TabbedLineReader;
import org.theseed.samples.SampleId;
import org.theseed.utils.BaseReportProcessor;

/**
 * This command copies a tab-delimited file containing sample information and normalizes
 * the sample IDs.
 *
 * There are no positional parameters.  The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -i	input file (if not STDIN)
 * -c	index (1-based) or name of column containing sample IDs to be normalized
 *
 * @author Bruce Parrello
 *
 */
public class SampleNormalizeProcessor extends BaseReportProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(SampleNormalizeProcessor.class);
    /** input file stream */
    private TabbedLineReader inStream;
    /** sample ID column index */
    private int keyColIdx;

    // COMMAND-LINE OPTIONS

    /** input file name (if not STDIN) */
    @Option(name = "--input", aliases = { "-i" }, metaVar = "inFile.tbl", usage = "input file (if not STDIN)")
    private File inFile;

    /** input sample ID column */
    @Option(name = "--col", aliases = { "-c" }, metaVar = "sample_id",
            usage = "index (1-based) or name of sample ID input column")
    private String keyCol;

    @Override
    protected void setReporterDefaults() {
        this.keyCol = "1";
        this.inFile = null;
    }

    @Override
    protected void validateReporterParms() throws IOException, ParseFailureException {
        if (this.inFile == null) {
            log.info("Input will be taken from standard input.");
            this.inStream = new TabbedLineReader(System.in);
        } else if (! this.inFile.canRead())
            throw new FileNotFoundException("Input file " + this.inFile + " is not found or unreadable.");
        else {
            log.info("Input will be read from {}.", this.inFile);
            this.inStream = new TabbedLineReader(this.inFile);
        }
        // Compute the key column.
        this.keyColIdx = this.inStream.findField(this.keyCol);
    }

    @Override
    protected void runReporter(PrintWriter writer) throws Exception {
        try {
            // Write the header line.
            writer.println(this.inStream.header());
            // Loop through the data lines, normalizing the sample ID.
            int count = 0;
            for (TabbedLineReader.Line line : this.inStream) {
                String[] fields = line.getFields();
                SampleId sample = new SampleId(fields[keyColIdx]);
                fields[keyColIdx] = sample.normalizeSets().toString();
                writer.println(StringUtils.join(fields, '\t'));
                count++;
            }
            log.info("All done.  {} lines processed.", count);
        } finally {
            this.inStream.close();
        }
    }

}
