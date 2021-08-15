/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.MarkerFile;
import org.theseed.rna.AdvancedExpressionConverter;
import org.theseed.rna.RnaData;
import org.theseed.rna.RnaJobInfo;
import org.theseed.rna.RnaSeqFeatureFilter;
import org.theseed.rna.RnaFeatureData;
import org.theseed.utils.BaseProcessor;
import org.theseed.utils.ParseFailureException;

/**
 * This command creates a classifier training file from the RNA seq data.  First, all the samples with threonine production data
 * will be isolated.  Each feature that appears as exact and real-valued in every one will be construed as an input column.
 * The output column will be the production class.  The actual production and density will be retained as meta-data, and the
 * sample ID will be used as an ID column.
 *
 * The positional parameters are the name of the RNA seq database and the name of the output directory.
 *
 * The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 *
 * --minGood	percent of samples that must have data on a feature for it to be considered useful (default is 90)
 * --all		include low-quality samples
 * --filter		method for filtering features (NONE, SUBSYSTEMS, MODULONS, FILE, GROUP)
 * --sub		if specified, the name of a GTO file; only features in the GTO's subsystems will be output (filter = SUBSYSTEMS)
 * --mod		if specified, the name of a regulon/modulon file; only features in modulons will be output (filter = MODULONS)
 * --filterFile	the name of a file to use in filtering: a tab-delimited file (with headers) containing the features to include in its first column (filter = FILE)
 * 				and optionally, the name of the feature's group in the second column (filter = GROUP); a GTO containing subsystem definitions (filter = SUBSYSTEMS);
 * 				a regulon/modulon file used to restrict the features to modulons (filter = MODULONS)
 * --method		method for reporting the expression values (RAW TRIAGE)
 * --minFeats	percent of features in a sample that must have data for the sample to be good (default is 50)
 * --baseFile	name of file containing baseline data for TRIAGE
 *
 * @author Bruce Parrello
 *
 */
public class RnaSeqClassProcessor extends BaseProcessor implements AdvancedExpressionConverter.IParms, RnaSeqFeatureFilter.IParms {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(RnaSeqClassProcessor.class);
    /** RNA sequence database */
    private RnaData data;
    /** filtering object */
    private RnaSeqFeatureFilter filter;
    /** expression converter */
    private AdvancedExpressionConverter converter;

    // COMMAND-LINE OPTIONS

    /** minimum percent of good values required to use a peg */
    @Option(name = "--minGood", metaVar = "95", usage = "minimum percent of expression values that must be good for each peg used")
    private int minGood;

    /** if specified, suspicious samples will be included */
    @Option(name = "--all", usage = "include suspicious samples")
    private boolean useAll;

    /** mininum percent of good pegs required to use a sample */
    @Option(name = "--minFeats", metaVar = "50", usage = "minimum percent of expression values that must be good for each sample used")
    private int minFeats;

    /** method to use for filtering features */
    @Option(name = "--filter", usage = "type of feature filtering/grouping to use")
    private RnaSeqFeatureFilter.Type filterType;

    /** file used to define filtering */
    @Option(name = "--filterFile", usage = "file used to refine filtering")
    private File filterFile;

    /** method to use for converting expression value */
    @Option(name = "--method", usage = "method to use for converting expression value to input value")
    private AdvancedExpressionConverter.Type method;

    /** file containing baseline values */
    @Option(name = "--baseFile", usage = "file containing baseline values for triage output")
    private File baseFile;

    /** name of the RNA seq database file */
    @Argument(index = 0, metaVar = "rnaData.ser", usage = "name of RNA Seq database file", required = true)
    private File rnaDataFile;

    /** name of the output directory */
    @Argument(index = 1, metaVar = "modelDir", usage = "output directory", required = true)
    private File outDir;

    @Override
    protected void setDefaults() {
        this.minGood = 90;
        this.minFeats = 50;
        this.useAll = false;
        this.method = AdvancedExpressionConverter.Type.RAW;
        this.baseFile = null;
        this.filterType = RnaSeqFeatureFilter.Type.NONE;
        this.filterFile = null;
    }

    @Override
    protected boolean validateParms() throws IOException, ParseFailureException {
        // Load the RNA database.
        if (! this.rnaDataFile.exists())
            throw new FileNotFoundException("RNA Seq data file " + this.rnaDataFile + " not found.");
        log.info("Loading RNA seq data from {}.", this.rnaDataFile);
        try {
            this.data = RnaData.load(this.rnaDataFile);
        } catch (ClassNotFoundException e) {
            throw new ParseFailureException("Version error in " + this.rnaDataFile + ": " + e.getMessage());
        }
        // Insure the thresholds are valid.
        if (this.minGood > 100)
            throw new ParseFailureException("Invalid minGood threshold.  Must be 100 or less.");
        if (this.minFeats > 100)
            throw new ParseFailureException("Invalid minFeats threshold.  Must be 100 or less.");
        // Create the filter.
        log.info("Initializing {} feature filter.", this.filterType);
        this.filter = this.filterType.create(this);
        this.filter.initialize();
        // Create the expression converter.
        log.info("Initalizing {} expression converter.", this.method);
        this.converter = this.method.create(this);
        this.converter.initialize();
        // Insure the output directory is set up.
        if (! this.outDir.isDirectory()) {
            log.info("Creating output directory {}.", this.outDir);
            FileUtils.forceMkdir(this.outDir);
        }
        File labelFile = new File(this.outDir, "labels.txt");
        try (PrintWriter writer = new PrintWriter(labelFile)) {
            writer.println("None");
            writer.println("Low");
            writer.println("High");
        }
        File deciderFile = new File(this.outDir, "decider.txt");
        MarkerFile.write(deciderFile, "RandomForest");
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Isolate the samples of interest.  For each one, we need its column index.
        log.info("Searching for good samples.");
        Collection<RnaData.JobData> jobs = this.getJobs();
        Map<String, RnaJobInfo> jobMap = new HashMap<String, RnaJobInfo>(jobs.size());
        // Compute the number of good features needed in a sample.
        int fThreshold = (this.data.rows() * this.minFeats + 50) / 100;
        // Loop through the jobs, keeping the ones with production data.
        for (RnaData.JobData job : jobs) {
            if (Double.isFinite(job.getProduction()) && (this.useAll || ! job.isSuspicious())) {
                RnaJobInfo jobInfo = new RnaJobInfo(this.data, job);
                // Count the good features in this sample.
                int fCount = (int) this.data.getRows().stream().filter(x -> jobInfo.isValid(x)).count();
                if (fCount >= fThreshold)
                    jobMap.put(job.getName(), new RnaJobInfo(this.data, job));
            }
        }
        int numJobs = jobMap.size();
        log.info("{} good samples with production data found in database.", numJobs);
        // Compute the number of good rows required for a peg to be used.  We simulate rounding.
        int threshold = (numJobs * this.minGood + 50) / 100;
        // This will track the number of bad feature values found in the good rows.
        int totalValues = 0;
        double goodValues = 0.0;
        int goodFeats = 0;
        // Now we need to find the valid features.
        log.info("Searching for good pegs with threshold of {} samples.", threshold);
        for (RnaData.Row row : this.data) {
            RnaFeatureData feat = row.getFeat();
            if (this.filter.checkFeature(feat)) {
                int valid = (int) jobMap.values().stream().filter(x -> x.isValid(row)).count();
                if (valid >= threshold) {
                    totalValues += numJobs;
                    goodValues += valid;
                    this.filter.saveFeature(row);
                    goodFeats++;
                }
            }
        }
        log.info("{} good values found out of {} total values ({}%).  {} features selected for processing.", goodValues, totalValues, goodValues * 100.0 / totalValues, goodFeats);
        // Now the filter knows which features to keep and to which groups they belong.
        // Loop through the RNA data rows (one per feature), accumulating values.  In the easy case,
        // we are tilting the matrix so each row is a sample instead of a feature.  In the complicated case,
        // we are also merging the values into groups.
        this.filter.initializeRows(jobMap.keySet());
        for (RnaData.Row row : this.data) {
            // Get this feature's ID.
            String fid = row.getFeat().getId();
            // Loop through the samples, processing the good values in this feature's row.
            for (Map.Entry<String, RnaJobInfo> jobEntry : jobMap.entrySet()) {
                RnaData.Weight weight = row.getWeight(jobEntry.getValue().getIdx());
                if (weight != null) {
                    String sample = jobEntry.getKey();
                    double weightValue = weight.getWeight();
                    if (weight.isExactHit() && Double.isFinite(weightValue))
                        this.filter.processValue(sample, fid, weightValue);
                }
            }
        }
        Map<String, double[]> sampleDataMap = this.filter.finishRows(this.converter);
        log.info("Data ready for output.");
        // Finally, we generate the output.
        List<String> colTitles = this.filter.getTitles();
        File dataFile = new File(this.outDir, "data.tbl");
        try (PrintWriter writer = new PrintWriter(dataFile)) {
            this.writeHeader(writer, colTitles);
            log.info("Writing data rows.");
            for (Map.Entry<String, double[]> entry : sampleDataMap.entrySet()) {
                RnaJobInfo info = jobMap.get(entry.getKey());
                String growth = (Double.isFinite(info.getGrowth()) ? Double.toString(info.getGrowth()) : "");
                // Here we must convert the value of each expression column.
                double[] sampleData = entry.getValue();
                String dataCols = Arrays.stream(sampleData).mapToObj(v -> Double.toString(v)).collect(Collectors.joining("\t"));
                writer.format("%s\t%s\t%s\t%14.4f\t%s%n", entry.getKey(), dataCols, info.getProduction(), growth,
                        Production.getLevel(info.getProduction()));
            }
        }
        log.info("All done.");
    }

    /**
     * Write the header line to the data and training files.
     *
     * @param writer		writer for the data file
     * @param colTitles		list of input column titles
     *
     * @throws FileNotFoundException
     */
    private void writeHeader(PrintWriter writer, List<String> colTitles) throws FileNotFoundException {
        log.info("Writing header.");
        String header = String.format("sample_id\t%s\tproduction\tgrowth\tprod_level", StringUtils.join(colTitles, "\t"));
        writer.println(header);
        try (PrintWriter trainer = new PrintWriter(new File(this.outDir, "training.tbl"))) {
            trainer.println(header);
        }
    }

    @Override
    public File getBaseFile() {
        return this.baseFile;
    }

    /**
     * @return a list of the jobs for the RNA database
     */
    protected List<RnaData.JobData> getJobs() {
        return this.data.getSamples();
    }

    @Override
    public File getFilterFile() {
        return this.filterFile;
    }

}
