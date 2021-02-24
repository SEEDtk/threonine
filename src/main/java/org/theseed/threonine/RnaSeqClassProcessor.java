/**
 *
 */
package org.theseed.threonine;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Option;
import org.theseed.reports.NaturalSort;
import org.theseed.rna.RnaData;
import org.theseed.utils.ParseFailureException;

/**
 * This command creates a classifier training file from the RNA seq data.  First, all the samples with threonine production data
 * will be isolated.  Each feature that appears as exact and real-valued in every one will be construed as an input column.
 * The output column will be the production class.  The actual production and density will be retained as meta-data, and the
 * sample ID will be used as an ID column.
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
 * --minQual	minimum percent quality for a sample to be considered valid (default is 40)
 *
 * @author Bruce Parrello
 *
 */
public class RnaSeqClassProcessor extends RnaSeqBaseProcessor {

    // COMMAND-LINE OPTIONS

    /** minimum percent of good values required to use a peg */
    @Option(name = "--minGood", metaVar = "95", usage = "minimum percent of expression values that must be good for each peg used")
    private int minGood;
    /** minimum percent quality rating for an acceptable sample */
    @Option(name = "--minQual", metaVar = "80", usage = "minimum percent quality for a sample to be considered valid")
    private double minQual;

    /**
     * This class contains the information about a sample we need to process it.
     */
    private static class JobInfo {

        /** column index where we can find the job's weight */
        private int colIdx;
        /** production amount */
        private double production;
        /** optical density */
        private double growth;

        /**
         * Extract the information we need to process a particular sample.
         *
         * @param database	parent RNA seq database
         * @param job		job descriptor
         */
        protected JobInfo(RnaData database, RnaData.JobData job) {
            this.colIdx = database.getColIdx(job.getName());
            this.production = job.getProduction();
            this.growth = job.getOpticalDensity();
        }

        /**
         * @return the sample's expression data for a feature
         *
         * @param featureRow	RNA seq database row for the feature
         */
        public double getExpression(RnaData.Row featureRow) {
            RnaData.Weight weight = featureRow.getWeight(this.colIdx);
            double retVal = 0.0;
            if (weight != null) {
                retVal = weight.getWeight();
                if (! Double.isFinite(retVal))
                    retVal = 0.0;
            }
            return retVal;
        }

        /**
         * @return TRUE if the sample's expression data is valid for a feature
         *
         * @param featureRow	RNA seq database row for the feature
         */
        public boolean isValid(RnaData.Row featureRow) {
            RnaData.Weight weight = featureRow.getWeight(this.colIdx);
            boolean retVal = (weight != null);
            if (retVal)
                retVal = weight.isExactHit() && Double.isFinite(weight.getWeight());
            return retVal;
        }

        /**
         * @return the production amount
         */
        public double getProduction() {
            return this.production;
        }

        /**
         * @return the optical density (growth)
         */
        public double getGrowth() {
            return this.growth;
        }

    }


    @Override
    protected void setDefaults() {
        this.minGood = 90;
        this.minQual = 40;
        this.setBaseDefaults();
    }

    @Override
    protected boolean validateParms() throws IOException, ParseFailureException {
        // Load the RNA database.
        this.loadRnaData();
        // Connect the output stream.
        this.setupOutput();
        // Insure the threshold is valid.
        if (this.minGood > 100)
            throw new ParseFailureException("Invalid minGood threshold.  Must be 100 or less.");
        if (this.minQual >= 100.0)
            throw new ParseFailureException("Invalid minQual threshold.  Must be less than 100.");
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Isolate the samples of interest.  For each one, we need its column index.
        log.info("Searching for good samples.");
        Collection<RnaData.JobData> jobs = this.getJobs();
        Map<String, JobInfo> jobMap = new HashMap<String, JobInfo>(jobs.size());
        // Loop through the jobs, keeping the ones with production data.
        for (RnaData.JobData job : jobs) {
            if (Double.isFinite(job.getProduction()) && job.getQuality() >= this.minQual)
                 jobMap.put(job.getName(), new JobInfo(this.getData(), job));
        }
        int numJobs = jobMap.size();
        log.info("{} good samples with production data found in database.", numJobs);
        // Compute the number of good rows required for a peg to be used.  We simulate rounding.
        int threshold = (numJobs * this.minGood + 50) / 100;
        // This will track the number of bad feature values found in the good rows.
        int totalValues = 0;
        double goodValues = 0.0;
        // Now we need to find the valid features.
        log.info("Searching for good pegs with threshold of {} samples.", threshold);
        SortedSet<String> goodFids = new TreeSet<String>(new NaturalSort());
        for (RnaData.Row row : this.getData()) {
            String fid = row.getFeat().getId();
            int valid = (int) jobMap.values().stream().filter(x -> x.isValid(row)).count();
            if (valid >= threshold) {
                totalValues += numJobs;
                goodValues += valid;
                goodFids.add(fid);
            }
        }
        log.info("{} good features found for the good samples.  {}% of the values were good.",
                goodFids.size(), Math.round(goodValues * 100 / totalValues));
        // We will keep column titles in here.
        List<String> colTitles = new ArrayList<String>(goodFids.size());
        // This will hold the expression levels for each sample.
        Map<String, double[]> sampleDataMap =
                jobMap.keySet().stream().collect(Collectors.toMap(x -> x, x -> new double[goodFids.size()]));
        // Build a data row for each sample.
        log.info("Collecting data for each sample.");
        for (RnaData.Row row : this.getData()) {
            String fid = row.getFeat().getId();
            if (goodFids.contains(fid)) {
                // Here we are going to use this row.  Build its column title.
                String gene = row.getFeat().getGene();
                if (gene.isEmpty())
                    colTitles.add("p" + StringUtils.substringAfter(fid, ".peg"));
                else
                    colTitles.add(gene);
                // Now get its column index.
                int i = colTitles.size() - 1;
                // Process each sample.
                for (Map.Entry<String, JobInfo> entry : jobMap.entrySet()) {
                    double[] sampleData = sampleDataMap.get(entry.getKey());
                    sampleData[i] = entry.getValue().getExpression(row);
                }
            }
        }
        log.info("{} feature rows processed.", colTitles.size());
        // Finally, we generate the output.
        try (PrintWriter writer = this.getWriter()) {
            log.info("Writing header.");
            writer.format("sample_id\t%s\tproduction\tgrowth\tprod_level%n", StringUtils.join(colTitles, "\t"));
            log.info("Writing data rows.");
            for (Map.Entry<String, double[]> entry : sampleDataMap.entrySet()) {
                JobInfo info = jobMap.get(entry.getKey());
                String growth = (Double.isFinite(info.getGrowth()) ? Double.toString(info.getGrowth()) : "");
                String dataCols = Arrays.stream(entry.getValue()).mapToObj(v -> Double.toString(v)).collect(Collectors.joining("\t"));
                writer.format("%s\t%s\t%s\t%14.4f\t%s%n", entry.getKey(), dataCols, growth, info.getProduction(),
                        Production.getLevel(info.getProduction()));
            }
        }
        log.info("All done.");
    }

}
