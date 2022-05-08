/**
 *
 */
package org.theseed.threonine;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.kohsuke.args4j.Argument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.TabbedLineReader;
import org.theseed.utils.BasePipeProcessor;
import org.theseed.utils.ParseFailureException;

/**
 * This method produces a report on the success of the predictors for a particular run.  The input must be
 * a big production table containing the relevant predictions.  A regular expression is used to determine
 * which experiments belong to the run.  Samples that were ONLY present in that run will be isolated and
 * put into a table.  The sensitivity, miss rate, and fallout for each possible cutoff will be output in
 * a report.
 *
 * The big production table should be present on the standard input.  The report will be written to the
 * standard output.  The output report can be used to generate an ROC graph (which is fallout vs sensitivity).
 *
 * The positional parameter is a regular expression that can be used to identify sample origins from the
 * target run.  It is presumed that the predictions in the file represent predictions from the run prior
 * to the target.
 *
 * The command-line options are as follows:
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -i	name of the input production file (if not STDIN)
 * -o	name of the output report file (if not STDOUT)
 *
 * @author Bruce Parrello
 *
 */
public class ProdAnalyzeProcessor extends BasePipeProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(ProdAnalyzeProcessor.class);
    /** list of prediction/production pairs for qualifying samples */
    private List<PredProd> samples;
    /** set of prediction values */
    private NavigableSet<Double> predictions;
    /** pattern for recognizing qualifying experiment IDs */
    private Pattern regPattern;
    /** prediction column index */
    private int predCol;
    /** production column index */
    private int prodCol;
    /** origin column index */
    private int originCol;
    /** splitter for origin strings */
    private static final Pattern DELIM = Pattern.compile(",\\s*");

    // COMMAND-LINE OPTIONS

    /** regular expression for identifying a qualifying experiment */
    @Argument(index = 0, metaVar = "regExp", usage = "regular expression for qualify experiment IDs", required = true)
    private String regExp;

    // NESTED CLASSES

    /**
     * This utility object contains a prediction value and a production value for a single sample.
     */
    protected static class PredProd implements Comparable<PredProd> {

        /** prediction level */
        private double prediction;
        /** production level */
        private double production;
        /** ID number */
        private int id;
        /** next available ID number */
        private static int NEXT_ID = 1;

        /**
         * Construct a prediction/production object.
         *
         * @param pred		prediction level
         * @param prod		production level
         */
        protected PredProd(double pred, double prod) {
            this.prediction = pred;
            this.production = prod;
            this.id = NEXT_ID;
            NEXT_ID++;
        }

        /**
         * We sort by prediction, then production (both descending), then ID
         */
        @Override
        public int compareTo(PredProd o) {
            int retVal = Double.compare(o.prediction, this.prediction);
            if (retVal == 0) {
                retVal = Double.compare(o.production, this.production);
                if (retVal == 0)
                    retVal = this.id - o.id;
            }
            return retVal;
        }

        /**
         * @return 1 if the prediction level is greater than or equal to the specified value, else 0
         *
         * @param cutoff		value to check
         */
        protected int isPrediction(double cutoff) {
            return (this.prediction >= cutoff ? 1 : 0);
        }

        /**
         * @return 1 if the production level is greater than or equal to the specified value, else 0
         *
         * @param cutoff		value to check
         */
        protected int isProduction(double cutoff) {
            return (this.production >= cutoff ? 1 : 0);
        }

    }

    // METHODS

    @Override
    protected void setPipeDefaults() {
    }

    @Override
    protected void validatePipeInput(TabbedLineReader inputStream) throws IOException {
        // Verify that we have a good production table.
        this.prodCol = inputStream.findField("thr_production");
        this.predCol = inputStream.findField("prediction");
        this.originCol = inputStream.findField("origins");
    }

    @Override
    protected void validatePipeParms() throws IOException, ParseFailureException {
        // Convert the regular expression to a pattern.
        this.regPattern = Pattern.compile(this.regExp);
    }

    @Override
    protected void runPipeline(TabbedLineReader inputStream, PrintWriter writer) throws Exception {
        // This will hold the predictions found.
        this.predictions = new TreeSet<Double>();
        // The first task is to run through the input file, extracting valid samples and sorting them
        // into the main structure.
        int scanCount = 0;
        this.samples = new ArrayList<PredProd>(1000);
        log.info("Scanning input for new samples.");
        for (TabbedLineReader.Line line : inputStream) {
            String[] origins = DELIM.split(line.get(this.originCol));
            // The sample is new if all its origins are new.
            boolean newSample = true;
            for (String origin : origins) {
                // Strip any parentheses.
                if (origin.startsWith("("))
                    origin = origin.substring(1, origin.length() - 1);
                if (! this.regPattern.matcher(origin).matches())
                    newSample = false;
            }
            scanCount++;
            if (newSample) {
                // Here the sample is entirely new.
                double prediction = line.getDouble(this.predCol);
                double production = line.getDouble(this.prodCol);
                var sampleEntry = new PredProd(prediction, production);
                this.samples.add(sampleEntry);
                if (prediction > 0.0)
                    this.predictions.add(prediction);
            }
        }
        log.info("{} new samples found out of {}.", this.samples.size(), scanCount);
        // Now we run through the prediction values in reverse order, writing output.
        writer.println("pred_level\ttp\tfp\ttn\tfn\tsensitivity\tmissRate\tfallout\taccuracy");
        for (double prediction : this.predictions) {
            // Confusion matrix:  0 = false, 1 = true; first is prediction, second is actual
            int[][] confusion = new int[][] { { 0, 0 }, { 0, 0 } };
            for (PredProd sampleEntry : this.samples)
                confusion[sampleEntry.isPrediction(prediction)][sampleEntry.isProduction(prediction)]++;
            final double totalPositive = confusion[1][1] + confusion[0][1];
            final double totalNegative = confusion[1][0] + confusion[0][0];
            double sensitivity = (totalPositive == 0.0 ? 0.0 : confusion[1][1] / totalPositive);
            double missRate = (totalPositive == 0.0 ? 0.0 : confusion[0][1] / totalPositive);
            double fallout = (totalNegative == 0.0 ? 0.0 : confusion[1][0] / totalNegative);
            double accuracy = (confusion[0][0] + confusion[1][1]) / (totalPositive + totalNegative);
            writer.format("%6.4f\t%d\t%d\t%d\t%d\t%6.4f\t%6.4f\t%6.4f\t%6.4f%n", prediction, confusion[1][1],
                    confusion[1][0], confusion[0][0], confusion[0][1], sensitivity, missRate, fallout,
                    accuracy);
        }
        log.info("{} prediction levels output.", this.predictions.size());
    }

}
