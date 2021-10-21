/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.rna.RnaData;
import org.theseed.utils.BaseProcessor;
import org.theseed.utils.ParseFailureException;

/**
 * This is the base class for commands that extract RNA Seq data from a database to an output report.
 * It manages the data itself and the output stream.
 *
 * @author Bruce Parrello
 *
 */
public abstract class RnaSeqBaseProcessor extends BaseProcessor {

    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(RnaSeqBaseProcessor.class);
    /** RNA sequence database */
    private RnaData data;
    /** output stream for report */
    private OutputStream outStream;

    // COMMAND-LINE OPTIONS

    /** output file, if not STDIN */
    @Option(name = "-o", aliases = { "--output" }, metaVar = "report.txt", usage = "output file name (if not STDOUT)")
    private File outFile;
    /** name of the RNA seq database file */
    @Argument(index = 0, metaVar = "rnaData.ser", usage = "name of RNA Seq database file", required = true)
    private File rnaDataFile;

    /**
     * Set the defaults for this object's command-line parameters.
     */
    public void setBaseDefaults() {
        this.outFile = null;
    }

    /**
     * Prepare the output stream.
     *
     * @throws IOException
     */
    protected void setupOutput() throws IOException {
        // Establish the output stream.
        if (this.outFile == null) {
            log.info("Report will be written to standard output.");
            this.outStream = System.out;
        } else {
            log.info("Report will be written to {}.", this.outFile);
            this.outStream = new FileOutputStream(this.outFile);
        }
    }

    /**
     * @return a print writer for the output stream
     */
    protected PrintWriter getWriter() {
        return new PrintWriter(this.outStream);
    }

    /**
     * @return a list of the jobs for the RNA database
     */
    protected List<RnaData.JobData> getJobs() {
        return this.getData().getSamples();
    }

    /**
     * Load the RNA database.
     *
     * @throws IOException
     * @throws ParseFailureException
     */
    protected void loadRnaData() throws IOException, ParseFailureException {
        if (! this.rnaDataFile.exists())
            throw new FileNotFoundException("RNA Seq data file " + this.rnaDataFile + " not found.");
        log.info("Loading RNA seq data from {}.", this.rnaDataFile);
        try {
            this.data = RnaData.load(this.rnaDataFile);
        } catch (ClassNotFoundException e) {
            throw new ParseFailureException("Version error in " + this.rnaDataFile + ": " + e.toString());
        }
    }

    /**
     * @return the RNA seq database
     */
    public RnaData getData() {
        return data;
    }

    /**
     * @return the fancy gene-name ID for a row
     *
     * @param row	row of interest
     */
    public static String computeGeneId(RnaData.Row row) {
        String fid = row.getFeat().getId();
        String gene = row.getFeat().getGene();
        String suffix = StringUtils.substringAfter(fid, ".peg");
        if (gene.isEmpty()) gene = "peg";
        String retVal = gene + suffix;
        return retVal;
    }

}
