/**
 *
 */
package org.theseed.threonine;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

import org.kohsuke.args4j.Argument;
import org.theseed.basic.ParseFailureException;

/**
 * This command reads an RNA expression database and compares the expression levels of two or more samples, displaying the mean
 * absolute error and the standard deviation of the absolute error, as well as the features with the biggest differences.
 *
 * The positional parameters are the name of the RNA Seq database and the names of the samples to compare.
 *
 * The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -n	number of outliers to display; the default is 10
 * -o	output file; the default is to output to STDOUT
 *
 * @author Bruce Parrello
 *
 */
public class RnaSeqCompareProcessor extends RnaSeqCompareBaseProcessor {

    // COMMAND-LINE OPTIONS

    /** names of the samples to compare */
    @Argument(index = 1, metaVar = "seqID1 seqID2 ...", usage = "names of the samples to compare", required = true)
    private List<String> samples;

    @Override
    protected void setDefaults() {
        this.setCompareDefaults();
    }

    @Override
    protected boolean validateParms() throws IOException, ParseFailureException {
        // Get the RNA database.
        this.loadRnaData();
        // Connect the output stream.
        this.setupOutput();
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Open the output stream for writing.
        try (PrintWriter writer = this.getWriter()) {
            // Write the report.
            this.compareSamples(this.samples, writer);
        }
    }

}
