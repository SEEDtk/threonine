/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
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
import org.theseed.rna.BaselineComputer;
import org.theseed.rna.ExpressionConverter;
import org.theseed.rna.IBaselineParameters;
import org.theseed.rna.IBaselineProvider;
import org.theseed.rna.RnaData;
import org.theseed.rna.RnaData.Row;
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
 * --minFeats	percent of features in a sample that must have data for the sample to be good (default is 50)
 * --baseline	method for computing baseline value in triage output (TRIMEAN, SAMPLE, FILE)
 * --baseId		ID of the base sample for a SAMPLE baseline
 * --baseFile	name of file containing baseline data for a FILE baseline
 *
 * @author Bruce Parrello
 *
 */
public class RnaSeqClassProcessor extends RnaSeqBaseProcessor implements IBaselineProvider, IBaselineParameters {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(RnaSeqClassProcessor.class);
    /** set of features in subsystems */
    private Set<String> subFids;
    /** baseline computer */
    private BaselineComputer baselineComputer;


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

    /** mininum percent of good pegs required to use a sample */
    @Option(name = "--minFeats", metaVar = "50", usage = "minimum percent of expression values that must be good for each sample used")
    private int minFeats;

    /** method to use for converting expression value */
    @Option(name = "--method", usage = "method to use for converting expression value to input value")
    private ExpressionConverter.Type method;

    /** method for computing baseline for triage output */
    @Option(name = "--baseline", usage = "method for computing triage baseline")
    private BaselineComputer.Type baselineType;

    /** file containing baseline values */
    @Option(name = "--baseFile", usage = "file containing baseline values for triage type FILE output")
    private File baseFile;

    /** ID of sample containing baseline values */
    @Option(name = "--baseId", usage = "sample containing baseline values for triage type SAMPLE output")
    private String baseSampleId;

    @Override
    protected void setDefaults() {
        this.minGood = 90;
        this.minFeats = 50;
        this.useAll = false;
        this.subGenome = null;
        this.setBaseDefaults();
        this.method = ExpressionConverter.Type.RAW;
        this.baselineType = BaselineComputer.Type.TRIMEAN;
        this.baseFile = null;
        this.baseSampleId = null;
    }

    @Override
    protected boolean validateParms() throws IOException, ParseFailureException {
        // Load the RNA database.
        this.loadRnaData();
        // Connect the output stream.
        this.setupOutput();
        // Insure the thresholds are valid.
        if (this.minGood > 100)
            throw new ParseFailureException("Invalid minGood threshold.  Must be 100 or less.");
        if (this.minFeats > 100)
            throw new ParseFailureException("Invalid minFeats threshold.  Must be 100 or less.");
        // Verify the baseline computation.
        if (this.method == ExpressionConverter.Type.TRIAGE) {
            // Here we need to validate the baseline computation method.
            switch (this.baselineType) {
            case FILE:
                if (this.baseFile == null)
                    throw new ParseFailureException("Baseline value file required for baseline type FILE.");
                else if (! this.baseFile.canRead())
                    throw new FileNotFoundException("Baseline value file " + this.baseFile + " not found or unreadable.");
                break;
            case SAMPLE:
                if (this.baseSampleId == null)
                    throw new ParseFailureException("Baseline sample ID required for baseline type SAMPLE.");
                break;
            default:
                break;
            }
            // Create the computation object.
            this.baselineComputer = this.baselineType.create(this);
        }
        // Validate the subsystem limits.
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
        // Compute the number of good features needed in a sample.
        int fThreshold = (this.getData().rows() * this.minFeats + 50) / 100;
        // Loop through the jobs, keeping the ones with production data.
        for (RnaData.JobData job : jobs) {
            if (Double.isFinite(job.getProduction()) && (this.useAll || ! job.isSuspicious())) {
                RnaJobInfo jobInfo = new RnaJobInfo(this.getData(), job);
                // Count the good features in this sample.
                int fCount = (int) this.getData().getRows().stream().filter(x -> jobInfo.isValid(x)).count();
                if (fCount >= fThreshold)
                    jobMap.put(job.getName(), new RnaJobInfo(this.getData(), job));
            }
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

    @Override
    public double getBaseline(Row row) {
        return this.baselineComputer.getBaseline(row);
    }

    @Override
    public File getFile() {
        return this.baseFile;
    }

    @Override
    public String getSample() {
        return this.baseSampleId;
    }

}
