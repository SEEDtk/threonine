/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.LineReader;
import org.theseed.io.TabbedLineReader;
import org.theseed.io.TabbedLineReader.Line;
import org.theseed.samples.SampleId;
import org.theseed.utils.BaseReportProcessor;
import org.theseed.utils.ParseFailureException;

/**
 * This method reads a predictions file containing both predicted and expected threonine data and a prediction file of virtual
 * samples containing only sample IDs and predictions.  The expected data will be added to samples for which it is available.
 * This cannot be done with a text-based join, since multiple sample IDs can represent the same sample.
 * The positional parameters are the name of the virtual-sample prediction file and the name of the real-sample prediction file
 * (the big production table).
 *
 * The output will be to the standard output.
 *
 * The command-line options are as follows:
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -l	cutoff level for high-performing output (default 1.2)
 * -t	name of a file containing the IDs of the training samples
 * -a	name of the file for the analysis report (default "analysis.txt"
 * -o	name of output file (if not STDOUT)
 *
 * --pure	exclude questionable samples from the output
 * --super	cutoff level for super-high samples  (default 4.0)
 *
 * @author Bruce Parrello
 *
 */
public class MergePredictionsProcessor extends BaseReportProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(MergePredictionsProcessor.class);
    /** set of training samples */
    private Set<SampleId> trainingSet;
    /** confusion matrix [low, high] with actual as first index */
    private final int[][] matrix = new int[][] { { 0, 0 }, { 0, 0 }, { 0, 0 }};
    /** confusion matrix for last-run-only samples */
    private final int[][] newMatrix = new int[][] { { 0, 0 }, { 0, 0 }, { 0, 0 }};
    /** confusion matrix for old samples */
    private final int[][] oldMatrix = new int[][] { { 0, 0 }, { 0, 0 }, { 0, 0 }};
    /** level labels */
    private final static String[] LEVELS = new String[] { "Low", "High" };
    /** expected number of samples */
    private static final int EXPECTED_SAMPLES = 4000;

    // COMMAND-LINE OPTIONS

    /** cutoff level for high-performing samples */
    @Option(name = "--level", aliases = { "-l" }, metaVar = "2.0", usage = "cutoff level for high production")
    private double cutoffLevel;

    /** name of training-set file */
    @Option(name = "--trained", aliases = { "-t" }, metaVar = "trained.tbl", usage = "file of samples used for training")
    private File trainFile;

    /** if specified, questionable samples will be excluded in the output */
    @Option(name = "--pure", usage = "if specified, questionable samples will be excluded from the output")
    private boolean pureFlag;

    /** super-high sample cutoff level */
    @Option(name = "--super", metaVar = "6.0", usage = "cutoff level for super-high samples")
    private double superLevel;

    /** name of analysis report file */
    @Option(name = "--analysis", metaVar = "report.txt", usage = "name of output file for analysis report")
    private  File analysisFile;

    /** input virtual-sample file */
    @Argument(index = 0, metaVar = "virtual.predictions.tbl", usage = "file of predictions for virtual samples")
    private File virtualFile;

    /** input real-sample file */
    @Argument(index = 1, metaVar = "real.sample.tbl", usage = "file of density and production values for real samples")
    private File realFile;

    @Override
    protected void setReporterDefaults() {
        this.cutoffLevel = 1.2;
        this.trainFile = null;
        this.superLevel = 4.0;
        this.analysisFile = new File(System.getProperty("user.dir"), "analysis.txt");
        this.pureFlag = false;
    }

    @Override
    protected void validateReporterParms() throws IOException, ParseFailureException {
        if (! this.virtualFile.canRead())
            throw new FileNotFoundException("Virtual-sample file " + this.virtualFile + " is not found or unreadable.");
        if (! this.realFile.canRead())
            throw new FileNotFoundException("Real-sample file " + this.realFile + " is not found or unreadable.");
        if (this.cutoffLevel <= 0.0)
            throw new ParseFailureException("Cutoff level must be positive.");
        if (this.superLevel <= this.cutoffLevel)
            throw new ParseFailureException("Super level must be greater than cutoff level.");
        if (! this.analysisFile.getParentFile().isDirectory())
            throw new FileNotFoundException("Analysis file " + this.analysisFile + " is not in a valid directory.");
        if (this.trainFile == null)
            this.trainingSet = Collections.emptySet();
        else if (! this.trainFile.canRead())
            throw new FileNotFoundException("Training-set file " + this.trainFile + " is not found or unreadable.");
        else {
            // We have a training-set file, so we can create the training set.
            this.trainingSet = LineReader.readSet(this.trainFile).stream().map(x -> new SampleId(x))
                    .collect(Collectors.toSet());
        }
    }

    @Override
    protected void runReporter(PrintWriter writer) throws Exception {
        // This will map each real sample to its production level.
        Map<SampleId, Integer> rowMap = new HashMap<SampleId, Integer>(EXPECTED_SAMPLES);
        // This will contain the set of samples run at any time.
        Set<SampleId> realSamples = new HashSet<SampleId>(EXPECTED_SAMPLES);
        // This will contain the set of samples exclusive to the last run.
        Set<SampleId> newSamples = new HashSet<SampleId>(EXPECTED_SAMPLES);
        // This will map each sample to its major output string.
        Map<SampleId, String> realMap = new HashMap<SampleId, String>(EXPECTED_SAMPLES);
        // This will count the super-high samples.
        int superSamples = 0;
        // This will count the bad samples skipped.
        int badCount = 0;
        // Loop through the input.
        try (TabbedLineReader realStream = new TabbedLineReader(this.realFile)) {
            int sampleCol = realStream.findField("sample");
            int densityCol = realStream.findField("growth");
            int prodCol = realStream.findField("thr_production");
            int baseCol = realStream.findField("base");
            int badCol = realStream.findField("bad");
            int numCols = realStream.size();
            for (TabbedLineReader.Line line : realStream) {
                // Skip this line if it is bad.
                String badFlag = line.get(badCol);
                if (badFlag.contentEquals("Y") ||
                        this.pureFlag && badFlag.contentEquals("?"))
                    badCount++;
                else {
                    SampleId sample = new SampleId(line.get(sampleCol));
                    // Get the density.  (It may be blank.)
                    String density = line.get(densityCol);
                    // Figure out the production level.
                    double prod = line.getDouble(prodCol);
                    int level = this.computeLevel(prod);
                    // Figure out if this sample is new.
                    boolean newFlag = this.computeNew(line, baseCol, numCols);
                    if (newFlag)
                        newSamples.add(sample);
                    // Figure out if this sample is super-high.
                    if (prod >= this.superLevel)
                        superSamples++;
                    // Compute the display string.
                    String display = String.format("%2.4f\t%s\t%s", prod, density, LEVELS[level]);
                    realMap.put(sample, display);
                    // If this is NOT a training sample, get its production level.
                    if (! this.trainingSet.contains(sample)) {
                        rowMap.put(sample, level);
                    }
                    // Record this as a sample that was run.
                    realSamples.add(sample);
                }
            }
        }
        // This will count the samples in the confusion matrix.
        int count = 0;
        // This will count the old testing samples.
        int oldCount = 0;
        // Total number of high predictions not in old runs.
        int likelyCount = 0;
        // Total number of high predictions run in last run.
        int testCount = 0;
        // Start the output.  There is one line of output for each line of virtual input.
        try (TabbedLineReader virtStream = new TabbedLineReader(this.virtualFile)) {
            writer.println("sample_id\tpredicted\tproduction\tdensity\tprod_level\tpred_level\tnew");
            int sampleCol = virtStream.findField("sample_id");
            int predCol = virtStream.findField("predicted");
            for (TabbedLineReader.Line line : virtStream) {
                // Get this sample ID.
                String sampleId = line.get(sampleCol);
                SampleId sample = new SampleId(sampleId);
                // Default to blank production\density columns.
                String realData = realMap.getOrDefault(sample, "\t\t");
                // Get the prediction value and level.
                double pred = line.getDouble(predCol);
                int predLevel = this.computeLevel(pred);
                // Default to the sample not being new.
                String newFlag = "";
                // Update the confusion matrix.
                if (rowMap.containsKey(sample)) {
                    int r = rowMap.get(sample);
                    this.matrix[r][predLevel]++;
                    count++;
                    if (newSamples.contains(sample)) {
                        this.newMatrix[r][predLevel]++;
                        newFlag = "Y";
                        // This is a new sample.  If it was predicted high, count it
                        // as a likely sample that was tested.
                        if (predLevel == 1) {
                            likelyCount++;
                            testCount++;
                        }
                    } else {
                        this.oldMatrix[r][predLevel]++;
                        oldCount++;
                    }
                } else if (! realSamples.contains(sample) && predLevel == 1) {
                    // Here a virtual sample that was never run was predicted high.
                    // Count it as a likely sample.
                    likelyCount++;
                }
                // Write the output line.
                writer.format("%s\t%2.4f\t%s\t%s\t%s%n", sample.toString(), pred, realData,
                        LEVELS[predLevel], newFlag);
            }
        }
        // Now output the confusion matrix.
        log.info("Writing analysis report.");
        try (PrintWriter aWriter = new PrintWriter(this.analysisFile)) {
            aWriter.format("%d bad samples were skipped.%n%n", badCount);
            aWriter.println("Confusion matrix for all samples: (row = actual, col = predicted)");
            aWriter.println();
            this.printConfusion(aWriter, this.matrix, count);
            aWriter.println();
            aWriter.println("Confusion matrix for new samples: (row = actual, col = predicted)");
            aWriter.println();
            this.printConfusion(aWriter, this.newMatrix, newSamples.size());
            aWriter.println();
            aWriter.println("Confusion matrix for old testing samples: (row = actual, col = predicted)");
            aWriter.println();
            this.printConfusion(aWriter, this.oldMatrix, oldCount);
            aWriter.println();
            aWriter.format("%d samples were super-high.%n", superSamples);
            aWriter.format("%d samples were likely.  Of these, %d were tested and %d worked.%n",
                    likelyCount, testCount, this.newMatrix[1][1]);
            aWriter.format("The success rate was %6.2f%n", this.newMatrix[1][1] * 100.0 / testCount);
        }
    }

    /**
     * Write the data and statistics for the specified confusion matrix.
     *
     * @param writer	output print writer
     * @param myMatrix	confusion matrix (row = actual, col = predicted)
     * @param count		total number of samples
     */
    private void printConfusion(PrintWriter writer, int[][] myMatrix, int count) {
        writer.format("%8s\t%8s\t%8s%n", "", LEVELS[0], LEVELS[1]);
        for (int i = 0; i < 2; i++)
            writer.format("%8s\t%8d\t%8d%n", LEVELS[i], myMatrix[i][0], myMatrix[i][1]);
        writer.println();
        int tp = myMatrix[1][1];
        int fp = myMatrix[0][1];
        int tn = myMatrix[0][0];
        int fn = myMatrix[1][0];
        double accuracy = (tp + tn) * 100.0 / count;
        double sensitivity = tp * 100.0 / (tp + fn);
        double fallout = fp * 100.0 / (fp + tn);
        writer.format("Accuracy is %6.2f%%%n", accuracy);
        writer.format("Sensitivity is %6.2f%%. This the the percent of high-performing samples we expect to catch.%n",
                sensitivity);
        writer.format("Fallout is %6.2f%%.  This is the percent of low-performing samples we expect to mis-label.%n",
                fallout);
        writer.println();
    }

    /**
     * @return TRUE if the current sample is exclusive to the last run (new), else FALSE
     *
     * @param line		input line
     * @param baseCol	column for base run; all subsequent columns are from later runs
     * @param numCols	the total number of columns in a line
     *
     */
    private boolean computeNew(Line line, int baseCol, int numCols) {
        int newCol = numCols - 1;
        // Was this sample in the new run at all?
        boolean retVal = line.getFlag(newCol);
        // Loop until we find a counter-indication.
        for (int i = baseCol; retVal && i < newCol; i++) {
            if (line.getFlag(i)) retVal = false;
        }
        return retVal;
    }

    /**
     * @return the array index of this threonine level (None, Low, High)
     *
     * @param pred	prediction level to convert
     */
    private int computeLevel(double pred) {
        int retVal;
        if (pred < this.cutoffLevel)
            retVal = 0;
        else
            retVal = 1;
        return retVal;
    }

}
