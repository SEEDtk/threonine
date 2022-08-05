/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.utils.BaseProcessor;
import org.theseed.utils.ParseFailureException;

/**
 * This command uses an unlimited-thrall prediction file to analyze the impact of different
 * input features on the output levels in a model.
 *
 * The positional parameters are the output file name, and then one or more model directory
 * names.  Each must contain a thrall prediction file.  The files contain the sample ID in the
 * first column, then all of the input features, then a prediction column.  The name of the thrall
 * prediction file should be "thrall.unlimited.results.tbl", but this can be overridden by a
 * command-line option
 *
 * The output file will be in Excel format.
 *
 * The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 *
 * --name		base name of the thrall prediction file in each model directory
 * --cutoff		prediction value to use for statistics on high/low results; default is 1.2
 *
 * @author Bruce Parrello
 *
 */
public class ThrImpactProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(ThrImpactProcessor.class);

    // COMMAND-LINE OPTIONS

    /** base name of each thrall prediction file */
    @Option(name = "--name", metaVar = "thrall.predictions.tbl", usage = "base name of each thrall prediction file")
    private String thrallName;

    /** cutoff for high-producing results */
    @Option(name = "--cutoff", metaVar = "2.0", usage = "minimum output value considered high")
    private double cutoff;

    /** output file name */
    @Argument(index = 0, metaVar = "outfile.xlsx", usage = "Excel output file", required = true)
    private File outFile;

    /** input model directory names */
    @Argument(index = 1, metaVar = "inDir1 inDir2 ...", usage = "input model directories", required = true)
    private List<File> inDir;

    @Override
    protected void setDefaults() {
        this.inDir = new ArrayList<File>();
        this.cutoff = 1.2;
        this.thrallName = "thrall.unlimited.results.tbl";
    }

    @Override
    protected boolean validateParms() throws IOException, ParseFailureException {
        // TODO code for validateParms
        return false;
    }

    @Override
    protected void runCommand() throws Exception {
        // TODO code for runCommand

    }
    // FIELDS
    // TODO data members for ThrImpactProcessor

    // TODO constructors and methods for ThrImpactProcessor
}
