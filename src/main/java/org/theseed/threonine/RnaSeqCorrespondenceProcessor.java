/**
 *
 */
package org.theseed.threonine;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.commons.math3.stat.correlation.KendallsCorrelation;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.util.ResizableDoubleArray;
import org.kohsuke.args4j.Option;
import org.theseed.rna.RnaData;
import org.theseed.rna.RnaJobInfo;
import org.theseed.utils.ParseFailureException;

/**
 * This command reports on the correspondence between each feature's expression levels and the threonine output.  The
 * correspondence is computed using Kendall's Tau-b and Pearson's so that perfect correlation is 1.0 and perfectly negative
 * correlation is -1.0.  In addition, it produces other useful statistics about each feature's expression levels.
 *
 * The positional parameter is the name of the RNA seq database.
 *
 * The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -o	output file; the default is to output to STDOUT
 *
 * --minGood	percent of samples that must have data on a feature for it to be considered useful (default is 90)
 *
 * @author Bruce Parrello
 *
 */
public class RnaSeqCorrespondenceProcessor extends RnaSeqBaseProcessor {

    // COMMAND-LINE OPTIONS

    /** minimum percent of good values required to use a peg */
    @Option(name = "--minGood", metaVar = "95", usage = "minimum percent of expression values that must be good for each peg used")
    private int minGood;

    @Override
    protected void setDefaults() {
        this.setBaseDefaults();
        this.minGood = 90;
    }

    @Override
    protected boolean validateParms() throws IOException, ParseFailureException {
        this.loadRnaData();
        this.setupOutput();
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Isolate the samples of interest.  For each one, we need its column index.
        log.info("Searching for good samples.");
        Collection<RnaData.JobData> jobs = this.getJobs();
        List<RnaJobInfo> jobList = new ArrayList<RnaJobInfo>(jobs.size());
        // Loop through the jobs, keeping the ones with production data.
        for (RnaData.JobData job : jobs) {
            if (Double.isFinite(job.getProduction()) && ! job.isSuspicious())
                 jobList.add(new RnaJobInfo(this.getData(), job));
        }
        int numJobs = jobList.size();
        int minElements = (numJobs * this.minGood + 50) / 100;
        log.info("{} good samples with production data found in database. {} required per features.", numJobs, minElements);
        // Create the correlation processors.
        KendallsCorrelation correlator = new KendallsCorrelation();
        PearsonsCorrelation pCorrelator = new PearsonsCorrelation();
        // Start the output.
        try (PrintWriter writer = this.getWriter()) {
            writer.println("col_name\tcount\ttau_correlation\tp_correlation\tmean\tmedian\ttrimean\tsdev\tmin\tmax");
            // The tricky part here is we need two parallel double[] arrays, but we only keep the ones
            // where there is a valid expression level.  The arrays will be built in here.
            DescriptiveStatistics fidArray = new DescriptiveStatistics(numJobs);
            ResizableDoubleArray thrArray = new ResizableDoubleArray(numJobs);
            // Loop through the features.  Each corresponds to an RNA database row.  (The columns are samples.)
            for (RnaData.Row row : this.getData()) {
                fidArray.clear(); thrArray.clear();
                for (RnaJobInfo job : jobList) {
                    if (job.isValid(row)) {
                        double expression = job.getExpression(row);
                        fidArray.addValue(expression);
                        thrArray.addElement(job.getProduction());
                    }
                }
                // Verify that we have enough values.
                String fidName = computeGeneId(row);
                if (fidArray.getN() <= minElements)
                    log.warn("Too few points for a valid correlation on {}.", fidName);
                else {
                    double corr = correlator.correlation(thrArray.getElements(), fidArray.getValues());
                    double pCorr = pCorrelator.correlation(thrArray.getElements(), fidArray.getValues());
                    double mean = fidArray.getMean();
                    double sdev = fidArray.getStandardDeviation();
                    double median = fidArray.getPercentile(50.0);
                    double triMean = (2.0 * mean + fidArray.getPercentile(25.0) + fidArray.getPercentile(75.0)) / 4.0;
                    writer.format("%s\t%d\t%8.6f\t%8.6f\t%8.6f\t%8.6f\t%8.6f\t%8.6f\t%8.6f\t%8.6f%n", fidName,
                            fidArray.getN(), corr, pCorr, mean, median, triMean, sdev, fidArray.getMin(),
                            fidArray.getMax());
                }
            }
        }

    }

}
