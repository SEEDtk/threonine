/**
 *
 */
package org.theseed.threonine;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.regex.Pattern;

import org.kohsuke.args4j.Argument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.TabbedLineReader;
import org.theseed.reports.PredictionAnalyzer;
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
    private PredictionAnalyzer samples;
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
        // The first task is to run through the input file, extracting valid samples and sorting them
        // into the main structure.
        int scanCount = 0;
        this.samples = new PredictionAnalyzer();
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
                this.samples.add(prediction, production);
            }
        }
        log.info("{} new samples found out of {}.", this.samples.size(), scanCount);
        // Now we run through the prediction values in reverse order, writing output.
        double[] predictions = this.samples.getAllPredictions();
        int predOut = 0;
        writer.println("pred_level\ttp\tfp\ttn\tfn\tsensitivity\tmissRate\tfallout\taccuracy");
        for (double prediction : predictions) {
            if (prediction > 0.0) {
                var matrix = this.samples.getMatrix(prediction);
                writer.format("%6.4f\t%d\t%d\t%d\t%d\t%6.4f\t%6.4f\t%6.4f\t%6.4f%n", prediction, matrix.truePositiveCount(),
                        matrix.falsePositiveCount(), matrix.trueNegativeCount(), matrix.falseNegativeCount(),
                        matrix.sensitivity(), matrix.missRatio(), matrix.fallout(),
                        matrix.accuracy());
                predOut++;
            }
        }
        log.info("{} prediction levels output.", predOut);
    }

}
