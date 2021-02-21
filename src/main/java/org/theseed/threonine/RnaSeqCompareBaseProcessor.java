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
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.counters.RatingList;
import org.theseed.rna.RnaData;
import org.theseed.rna.RnaData.Row;
import org.theseed.utils.BaseProcessor;
import org.theseed.utils.ParseFailureException;

/**
 * This is a base class for RNA Seq expression data comparison.  It contains the key parameter, plus the
 * main method for generating comparison output.
 *
 * @author Bruce Parrello
 *
 */
public abstract class RnaSeqCompareBaseProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(RnaSeqCompareBaseProcessor.class);
    /** RNA sequence database */
    private RnaData data;
    /** output stream for report */
    private OutputStream outStream;

    // COMMAND-LINE OPTIONS

    /** number of outliers to display */
    @Option(name = "-n", aliases = { "--num" }, metaVar = "10", usage = "number of outliers to display")
    private int numOutliers;
    /** output file, if not STDIN */
    @Option(name = "-o", aliases = { "--output" }, metaVar = "report.txt", usage = "output file name (if not STDOUT)")
    private File outFile;
    /** name of the RNA seq database file */
    @Argument(index = 0, metaVar = "rnaData.ser", usage = "name of RNA Seq database file", required = true)
    private File rnaDataFile;

    /**
     * Set the defaults for this class's options.
     */
    protected void setBaseDefaults() {
        this.numOutliers = 10;
        this.outFile = null;
    }
    /**
     * Generate the report for a list of samples.
     *
     * @param samples		list of the IDs for the samples to compare
     * @param writer		output writer for the report
     *
     * @return a statistics object for the error spreads found
     */
    protected SummaryStatistics compareSamples(List<String> samples, PrintWriter writer) {
        // Get the list of relevant column indices.
        int[] cols = new int[samples.size()];
        for (int i = 0; i < cols.length; i++) {
            String sampleId = samples.get(i);
            Integer colIdx = this.data.findColIdx(sampleId);
            if (colIdx == null)
                throw new IllegalArgumentException("Invalid sample ID {}:  not found in RNA database.");
            cols[i] = colIdx;
        }
        // For each feature, we compute the spread between the highest and lowest values.  The biggest spreads
        // will be stored in this list
        RatingList<RnaData.FeatureData> outliers = new RatingList<>(this.numOutliers);
        // The key statistics will be stored in this object.
        SummaryStatistics retVal = new SummaryStatistics();
        log.info("Processing RNA data rows.");
        for (RnaData.Row row : this.data) {
            // Compute the spread.
            double range = this.computeSpread(row, cols);
            retVal.addValue(range);
            // Add the spread to the outlier list.
            outliers.add(row.getFeat(), range);
        }
        // Now, we do the report.  Identify the samples being compared.
        writer.println("SAMPLE COMPARISON");
        for (String sample : samples)
            writer.format("     %s%n", sample);
        writer.println();
        // Display the main stats.
        writer.format("     Mean spread is %1.2f with stdev %1.2f%n", retVal.getMean(), retVal.getStandardDeviation());
        // Now show the outliers.
        writer.println();
        String header = String.format("%-30s %-8s %14s", "Peg ID", "Gene", "Spread");
        String dashes = StringUtils.repeat('-', header.length());
        writer.format("     %s%n", header);
        writer.format("     %s%n", dashes);
        for (RatingList.Rating<RnaData.FeatureData> rating : outliers) {
            RnaData.FeatureData feat = rating.getKey();
            writer.format("     %-30s %-8s %14.2f%n", feat.getId(), feat.getGene(), rating.getRating());
        }
        return retVal;
    }

    /**
     * @return the range spread between the minimum and maximum expression values for the specified columns in this row
     *
     * @param row	feature row of interest
     * @param cols	indices of columns containing the samples of interest
     */
    private double computeSpread(Row row, int[] cols) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int colIdx : cols) {
            RnaData.Weight weight = row.getWeight(colIdx);
            if (weight != null) {
                double val = weight.getWeight();
                if (val < min) min = val;
                if (val > max) max = val;
            }
        }
        if (max < min) max = min;
        return max - min;
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
        return this.data.getSamples();
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
            throw new ParseFailureException("Version error in " + this.rnaDataFile + ": " + e.getMessage());
        }
    }

}
