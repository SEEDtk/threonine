/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.genome.Feature;
import org.theseed.genome.Genome;
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
 * --all		minimum percent quality for a sample to be considered valid (default is 40)
 * --sub		if specified, the name of a GTO file; only features in the GTO file's subsystems will be output
 * --method		method for reporting the expression values (RAW, STD, TRIAGE)
 *
 * @author Bruce Parrello
 *
 */
public class RnaSeqClassProcessor extends RnaSeqBaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(RnaSeqClassProcessor.class);
    /** set of features in subsystems */
    private Set<String> subFids;


    // COMMAND-LINE OPTIONS

    /** minimum percent of good values required to use a peg */
    @Option(name = "--minGood", metaVar = "95", usage = "minimum percent of expression values that must be good for each peg used")
    private int minGood;
    /** if specified, suspicious samples will be included */
    @Option(name = "--all", usage = "include suspicious samples")
    private boolean useAll;
    /** if specified, only features in subsystems will be used */
    @Option(name = "--sub", metaVar = "genome.gto", usage = "genome whose subsystems will be used to filter the features")
    private File subGenome;
    /** method to use for converting expression value */
    @Option(name = "--method", usage = "method to use for converting expression value to input value")
    private ExpressionConverter.Type method;

    @Override
    protected void setDefaults() {
        this.minGood = 90;
        this.useAll = false;
        this.subGenome = null;
        this.setBaseDefaults();
        this.method = ExpressionConverter.Type.RAW;
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
        if (this.subGenome == null) {
            this.subFids = null;
            log.info("No subsystem filtering will be used.");
        } else {
            Genome genome = new Genome(this.subGenome);
            this.subFids = new HashSet<String>(3000);
            for (Feature feat : genome.getPegs()) {
                if (! feat.getSubsystems().isEmpty())
                    this.subFids.add(feat.getId());
            }
            log.info("{} features found in subsystems of {}.", this.subFids.size(), genome);
        }
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Isolate the samples of interest.  For each one, we need its column index.
        log.info("Searching for good samples.");
        Collection<RnaData.JobData> jobs = this.getJobs();
        Map<String, RnaJobInfo> jobMap = new HashMap<String, RnaJobInfo>(jobs.size());
        // Loop through the jobs, keeping the ones with production data.
        for (RnaData.JobData job : jobs) {
            if (Double.isFinite(job.getProduction()) && (this.useAll || ! job.isSuspicious()))
                 jobMap.put(job.getName(), new RnaJobInfo(this.getData(), job));
        }
        int numJobs = jobMap.size();
        log.info("{} good samples with production data found in database.", numJobs);
        // Compute the number of good rows required for a peg to be used.  We simulate rounding.
        int threshold = (numJobs * this.minGood + 50) / 100;
        // This will track the number of bad feature values found in the good rows.
        int totalValues = 0;
        double goodValues = 0.0;
        // Now we need to find the valid features.  We will create a map of each feature's column
        // name to its feature ID.
        log.info("Searching for good pegs with threshold of {} samples.", threshold);
        SortedMap<String, String> goodFids = new TreeMap<String, String>(new NaturalSort());
        for (RnaData.Row row : this.getData()) {
            String fid = row.getFeat().getId();
            if (this.subFids == null || this.subFids.contains(fid)) {
                int valid = (int) jobMap.values().stream().filter(x -> x.isValid(row)).count();
                if (valid >= threshold) {
                    totalValues += numJobs;
                    goodValues += valid;
                    String fidName = this.computeGeneId(row);
                    goodFids.put(fidName, fid);
                }
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
        ExpressionConverter converter = this.method.create(this);
        log.info("Collecting data for each sample.");
        for (Map.Entry<String, String> fidEntry : goodFids.entrySet()) {
            RnaData.Row row = this.getData().getRow(fidEntry.getValue());
            colTitles.add(fidEntry.getKey());
            // Compute the column index of this feature.
            int i = colTitles.size() - 1;
            converter.analyzeRow(row);
            // Process each sample, filling in the feature's expression value.
            for (Map.Entry<String, RnaJobInfo> entry : jobMap.entrySet()) {
                double[] sampleData = sampleDataMap.get(entry.getKey());
                sampleData[i] = converter.getExpression(entry.getValue());
            }
        }
        log.info("{} feature rows processed.", colTitles.size());
        // Finally, we generate the output.
        try (PrintWriter writer = this.getWriter()) {
            log.info("Writing header.");
            writer.format("sample_id\t%s\tproduction\tgrowth\tprod_level%n", StringUtils.join(colTitles, "\t"));
            log.info("Writing data rows.");
            for (Map.Entry<String, double[]> entry : sampleDataMap.entrySet()) {
                RnaJobInfo info = jobMap.get(entry.getKey());
                String growth = (Double.isFinite(info.getGrowth()) ? Double.toString(info.getGrowth()) : "");
                String dataCols = Arrays.stream(entry.getValue()).mapToObj(v -> Double.toString(v)).collect(Collectors.joining("\t"));
                writer.format("%s\t%s\t%s\t%14.4f\t%s%n", entry.getKey(), dataCols, growth, info.getProduction(),
                        Production.getLevel(info.getProduction()));
            }
        }
        log.info("All done.");
    }

}
