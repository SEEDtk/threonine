/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.TabbedLineReader;
import org.theseed.reports.MeanComputer;
import org.theseed.samples.SampleId;
import org.theseed.utils.BaseProcessor;
import org.theseed.utils.ParseFailureException;

/**
 * This is a special-purpose script to reconcile the threonine growth and production data in the master file.
 *
 * Each row of the new strain file is identified in the old file by a strain name, an IPTG flag, and a time
 * stamp.  These three information items can be translated into a unique sample ID.  All rows that map to the
 * same sample ID will be averaged together to determine the correct growth and production data.  There is a
 * "Suspect" flag used to identify bad samples.  There are also the "experiment" and "Sample_y" columns containing
 * the original experiment ID and the well containing the sample.  Finally, we compute the normalized production
 * (raw / optical density) and the growth rate (production / time).
 *
 * The positional parameters are the name of the master file, the name of a file to contain the choices
 * for the parts of the strain name, and the name of the output file.
 *
 * The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 *
 * --mean		type of mean to use (MIDDLE, SIGMA2, SIGMA1, TRIMEAN, MAX)
 * --good		only output good samples
 * --alert		specifies a range; production values with a higher spread in values than
 * 				the specified range are flagged as questionable (the default is 1.0)
 * --trigger	specifies a threshold; if a time point is greater than all the other time
 * 				points (two or more) by the threshold, then the production value is flagged
 * 				as questionable
 * --time		if specified, only the indicated time point will be output; a negative value indicates no filtering
 * --iptg		if specified, only IPTG-positive samples will be output
 * --predict	name of a file containing predicted production values for the samples; the file should be tab-delimited,
 * 				with headers, the sample ID in column 1 and the prediction in column 2
 * --runFile	name of the run control file; if specified, the run control file must have at least two columns,
 * 				tab-delimited with headers, with the run name in the first column and the plate/well pattern in the
 * 				second; the runs must be in order; in the absence of this file, every experiment is assigned the run
 * 				"ALL"
 * --runs		if specified, the names of the runs to include, comma-delimited; the default is to include all runs
 * --fixed		exclude unfixed samples
 *
 * @author Bruce Parrello
 *
 */
public class ThrFixProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(ThrFixProcessor.class);
    /** accumulated growth data for each good sample ID */
    private SortedMap<SampleId, GrowthData> growthMap;
    /** accumulated growth data for each bad sample ID */
    private SortedMap<SampleId, GrowthData> badGrowthMap;
    /** strain fragment sets */
    private List<Set<String>> choices;
    /** map of run names to plate/well patterns */
    private Map<String, Pattern> runMap;
    /** set of runs to include */
    private Set<String> runs;

    // COMMAND-LINE OPTIONS

    /** only output good samples */
    @Option(name = "--good", usage = "only output good samples")
    private boolean goodFlag;

    /** maximum production range to be considered reliable */
    @Option(name = "--alert", metaVar = "0.1", usage = "maximum reliable production range")
    private double alertRange;

    /** type of mean to use for multi-valued samples */
    @Option(name = "--mean", usage = "algorithm for computing mean of multi-valued samples")
    private MeanComputer.Type meanType;

    /** trigger threshold */
    @Option(name = "--trigger", usage = "threshold for detecting anomalous time points")
    private double triggerThreshold;

    /** time filter */
    @Option(name = "--time", metaVar = "24.0", usage = "time point for time-point filtering, if specified")
    private double timeFilter;

    /** IPTG filter flag */
    @Option(name = "--iptg", usage = "if specified, only IPTG-positive samples will be output")
    private boolean iptgFilter;

    /** prediction input file */
    @Option(name = "--predict", metaVar = "thrall.predictions.tbl", usage = "if specified, a tab-delimited 2-column file containing predicted production levels")
    private File predictFile;

    /** run file name */
    @Option(name = "--runFile", metaVar = "runs.control.tbl", usage = "if specified, the run identification control file")
    private File runFile;

    /** name of last acceptable run */
    @Option(name = "--runs", metaVar = "21Jan,21Aug", usage = "if specified, a comma-delimited list of runs to include")
    private String runList;

    /** TRUE if only fixed samples should be included */
    @Option(name = "--fixFilter", usage = "if specified, only fixed samples will be included")
    private boolean fixFilter;

    /** old strain data file */
    @Argument(index = 0, metaVar = "oldStrains.tbl", usage = "old strain information file", required = true)
    private File oldFile;

    /** choices output file */
    @Argument(index = 1, metaVar = "choices.tbl", usage = "choices output file", required = true)
    private File choiceFile;

    /** main output file */
    @Argument(index = 2, metaVar = "output.tbl", usage = "production output file", required = true)
    private File outFile;

    @Override
    protected void setDefaults() {
        this.goodFlag = false;
        this.alertRange = 1.0;
        this.meanType = MeanComputer.Type.TRIMEAN;
        this.triggerThreshold = 1.2;
        this.timeFilter = -1.0;
        this.iptgFilter = false;
        this.predictFile = null;
        this.runFile = null;
        this.runList = null;
        this.fixFilter = false;
    }

     @Override
    protected boolean validateParms() throws ParseFailureException, IOException {
        // Verify the input files.
        if (! this.oldFile.canRead())
            throw new FileNotFoundException("Old strain input file " + this.oldFile + " is not found or unreadable.");
        // Store the mean type.
        GrowthData.MEAN_COMPUTER = this.meanType.create();
        log.info("Using {} to average multi-valued samples.", this.meanType);
        // Validate the prediction file.
        if (this.predictFile != null && ! this.predictFile.canRead())
            throw new FileNotFoundException("Prediction file " + this.predictFile + " is not found or unreadable.");
        // Set up the runs.
        if (this.runFile == null) {
            // No run filtering used.
            log.info("No run control file, so run-filtering suppressed.");
            this.runMap = Map.of("ALL", Pattern.compile(".+"));
            this.runs = Set.of("ALL");
        } else if (! this.runFile.canRead())
            throw new FileNotFoundException("Run control file " + this.runFile + " is not found or unreadable.");
        else {
            // Here we have a run control file.  Loop through it, saving the names and patterns.
            log.info("Run specifications will be read from {}.", this.runFile);
            try (TabbedLineReader runStream = new TabbedLineReader(this.runFile)) {
                this.runMap = runStream.stream().collect(Collectors.toMap(x -> x.get(0), x -> Pattern.compile(x.get(1))));
            }
            log.info("{} runs read from control file.", this.runMap.size());
            if (this.runList == null) {
                this.runs = this.runMap.keySet();
                log.info("All runs will be allowed.");
            } else {
                this.runs = Set.of(StringUtils.split(this.runList, ','));
                // Validate the runs selected.
                for (String run : this.runs) {
                    if (! this.runMap.containsKey(run))
                        throw new ParseFailureException("Run " + run + " not found in run list.");
                }
                log.info("{} runs will be included in output.", this.runs.size());
            }
        }
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // This tracks the bad strain names we've seen.
        Set<String> badStrainNames = new HashSet<String>();
        // Initialize the choices tracker.
        this.choices = new ArrayList<Set<String>>(SampleId.STRAIN_SIZE);
        while (this.choices.size() < SampleId.STRAIN_SIZE)
            this.choices.add(new TreeSet<String>());
        // Initialize the master map of sample IDs to growth data.
        this.growthMap = new TreeMap<SampleId, GrowthData>();
        this.badGrowthMap = new TreeMap<SampleId, GrowthData>();
        // Now we read the old strain file and create the output.
        log.info("Reading old sample input file {}.", this.oldFile);
        // If the flag field is blank we use the saved value.
        boolean iptgFlag = false;
        // If the strain field is blank er also use the saved value.
        String oldStrain = "";
        // Here we'll count rows we skipped due to missing numbers, untranslatable strains, or filtering.
        int badNumRows = 0;
        int badSampleRows = 0;
        int badStrainRows = 0;
        int keptRows = 0;
        int zeroProdRows = 0;
        int filterRows = 0;
        int excludedRows = 0;
        // Now loop through the file.
        try (TabbedLineReader oldStream = new TabbedLineReader(this.oldFile)) {
            int strainCol = oldStream.findField("strain_lower");
            int iptgCol = oldStream.findField("iptg");
            int timeCol = oldStream.findField("time");
            int prodCol = oldStream.findField("Thr");
            int densCol = oldStream.findField("Growth");
            int errCol = oldStream.findField("Suspect");
            int expCol = oldStream.findField("experiment");
            int wellCol = oldStream.findField("Sample_y");
            // Check for the fix column.
            int fixedCol = oldStream.findColumn("Fixed");
            if (fixedCol < 0)
                log.warn("WARNING:  No fixup flag column.");
            for (TabbedLineReader.Line line : oldStream) {
                // Verify that this line has numbers in the growth and production columns.
                if (line.isEmpty(prodCol) || line.isEmpty(densCol))
                    badNumRows++;
                else {
                    // Here we have good data.  Get the strain.
                    String strain;
                    if (line.isEmpty(strainCol))
                        strain = oldStrain;
                    else {
                        strain = line.get(strainCol);
                        oldStrain = strain;
                    }
                    // Note we skip strains named "Blank".
                    if (! StringUtils.startsWithIgnoreCase(strain, "Blank")) {
                        // Compute the IPTG flag.  Note an empty value simply keeps the old value.
                        if (! line.isEmpty(iptgCol))
                            iptgFlag = line.getFancyFlag(iptgCol);
                        // Compute the time point.
                        double time;
                        if (line.isEmpty(timeCol))
                            time = Double.NaN;
                        else
                            time = line.getDouble(timeCol);
                        // Get the experiment and well.
                        String exp = line.get(expCol);
                        String well = line.get(wellCol);
                        boolean fixFlag = (fixedCol >= 0 && line.getFlag(fixedCol));
                        // If the time is 4.5, IPTG is always FALSE.  It is not added until 5 hours.
                        boolean realIptg = iptgFlag && (time >= 5.0);
                        // Convert the strain to a sample ID.
                        SampleId sample = SampleId.translate(strain, time, realIptg, "M1");
                        if (sample == null) {
                            badStrainRows++;
                            if (! badStrainNames.contains(strain)) {
                                log.debug("Invalid input strain ID {}.", strain);
                                badStrainNames.add(strain);
                            }
                        } else if (this.iptgFilter && ! sample.isIPTG() || this.timeFilter >= 0.0 && sample.getTimePoint() != this.timeFilter
                                || this.fixFilter && ! fixFlag) {
                            // Here the sample is rejected by the filtering criteria.
                            filterRows++;
                        } else if (! this.acceptableRun(exp, well)) {
                            // Here the sample is from an excluded run.
                            excludedRows++;
                        } else {
                            // Update the choices.
                            String[] strainData = sample.getBaseFragments();
                            for (int i = 0; i < strainData.length; i++)
                                this.choices.get(i).add(strainData[i]);
                            for (String insert : sample.getInserts())
                                this.choices.get(SampleId.INSERT_COL).add(insert);
                            for (String delete : sample.getDeletes())
                                this.choices.get(SampleId.DELETE_COL).add(delete);
                            // Get the production, density, experiment ID, and well ID.
                            double prod = line.getDouble(prodCol);
                            double dens = line.getDouble(densCol);
                            // Determine if this row is good.  That determines which map it goes in.
                            Map<SampleId, GrowthData> targetMap;
                            if (line.getFancyFlag(errCol)) {
                                targetMap = this.badGrowthMap;
                                badSampleRows++;
                            } else {
                                targetMap = this.growthMap;
                                keptRows++;
                                if (prod == 0.0) zeroProdRows++;
                            }
                            // Store this row in the sample map.
                            GrowthData growth = targetMap.computeIfAbsent(sample, x -> new GrowthData(strain, time));
                            growth.merge(prod, dens, exp, well);
                            // Update the fix count.
                            if (fixFlag)
                                growth.countFix();
                        }
                    }
                }
            }
        }
        // Now apply the predictions.
        if (this.predictFile != null) {
            log.info("Reading predictions from {}.", this.predictFile);
            int count = 0;
            try (TabbedLineReader predictStream = new TabbedLineReader(this.predictFile)) {
                for (TabbedLineReader.Line line : predictStream) {
                    var sample = new SampleId(line.get(0));
                    // Find this sample in one of the maps.
                    var growthData = this.growthMap.get(sample);
                    if (growthData == null)
                        growthData = this.badGrowthMap.get(sample);
                    // If we found it, store the prediction.
                    if (growthData != null) {
                        growthData.setPrediction(line.getDouble(1));
                        count++;
                    }
                }
            }
            log.info("{} predictions stored for output.", count);
        }
        // Write out the choices information.
        int colCount = 1;
        try (PrintWriter printer = new PrintWriter(this.choiceFile)) {
            for (Set<String> options : this.choices) {
                printer.println(StringUtils.join(options, ", "));
                colCount += options.size() - 1;
            }
        }
        log.info("{} columns required for training set.", colCount);
        // Write out the other stats.
        log.info("{} rows had improperly-formatted strain names.  {} rows were removed by filtering", badStrainRows, filterRows);
        log.info("{} rows were missing growth or production numbers, {} input rows were suspect, and {} were good.",
                badNumRows, badSampleRows, keptRows);
        log.info("{} good rows had no production. {} were excluded due to run filtering,", zeroProdRows, excludedRows);
        // Analyze the good data.
        int qCount = 0;
        int aCount = 0;
        int gCount = 0;
        for (GrowthData growth : this.growthMap.values()) {
            // Fix any zero outliers.
            growth.removeBadZeroes(this.alertRange);
            // Check the alert range.
            boolean ok = growth.removeOutlier(this.alertRange);
            if (! ok) {
                growth.setSuspicious();
                aCount++;
            } else if (growth.isSuspicious()) {
                qCount++;
            } else
                gCount++;
        }
        // Now we need to organize the samples by strain and look for threshold anomalies.
        this.checkThresholds();
        // Write the results.
        log.info("Producing output to {}.", this.outFile);
        try (PrintWriter writer = new PrintWriter(this.outFile)) {
            writer.println("num\told_strain\tsample\tthr_production\tprediction\tdensity\tbad\tfixes\tunfixed\tthr_normalized\tthr_rate\torigins\traw_productions\traw_densities");
            int num = 0;
            for (Map.Entry<SampleId, GrowthData> sampleEntry : this.growthMap.entrySet()) {
                SampleId sampleId = sampleEntry.getKey();
                GrowthData growth = sampleEntry.getValue();
                num++;
                String qFlag = (growth.isSuspicious() ? "?" : "");
                writeSampleData(writer, num, sampleId, growth, qFlag);
            }
            log.info("{} good samples output, {} failed the alert check, {} were questionable.", gCount, aCount, qCount);
            // Write the bad data if the user wants it.
            if (! this.goodFlag) {
                int oldNum = num;
                for (Map.Entry<SampleId, GrowthData> sampleEntry : this.badGrowthMap.entrySet()) {
                    SampleId sampleId = sampleEntry.getKey();
                    if (this.growthMap.containsKey(sampleId)) {
                        GrowthData growth = sampleEntry.getValue();
                        num++;
                        writeSampleData(writer, num, sampleId, growth, "Y");
                    }
                }
                log.info("{} bad samples output.", num - oldNum);
            }
        }
    }

    /**
     * @return TRUE if this sample test is from an acceptable run, else FALSE
     *
     * @param exp		plate ID
     * @param well		well ID
     *
     * @throws IOException
     */
    private boolean acceptableRun(String exp, String well) throws IOException {
        String testId = exp + ":" + well;
        String retVal = null;
        for (Map.Entry<String, Pattern> runEntry : this.runMap.entrySet()) {
            if (runEntry.getValue().matcher(testId).matches())
                retVal = runEntry.getKey();
        }
        if (retVal == null)
            throw new IOException("Invalid experiment \"" + testId + "\" found:  cannot identify run.");
        else return this.runs.contains(retVal);
    }

    /**
     * Sort the growth data by sample.  Look for threshold anomalies; that is, strains for which a single sample has a dramatically
     * higher value at a particular time point.  We only look at good samples, and strains with three or more time points.
     */
    private void checkThresholds() {
        Map<String, NavigableSet<GrowthData>> strainMap = new HashMap<String, NavigableSet<GrowthData>>(this.growthMap.size());
        for (Map.Entry<SampleId, GrowthData> sampleEntry : this.growthMap.entrySet()) {
            // Compute the strain/induction ID for this sample.
            String strainId = sampleEntry.getKey().toTimeless();
            // Get its growth list.
            NavigableSet<GrowthData> growthList = strainMap.computeIfAbsent(strainId, k -> new TreeSet<GrowthData>());
            growthList.add(sampleEntry.getValue());
        }
        // For each strain, the samples are sorted from highest production to lowest.  So, if we have at least three samples in
        // the strain list and the gap between the first and second is higher than the threshold, we have a suspicious sample.
        int count = 0;
        for (NavigableSet<GrowthData> growthList : strainMap.values()) {
            if (growthList.size() >= 3) {
                GrowthData first = growthList.pollFirst();
                if ((first.getProduction() - growthList.first().getProduction()) > this.triggerThreshold) {
                    first.setSuspicious();
                    count++;
                }
            }
        }
        log.info("{} samples failed the threshold test for threshold {}.", count, this.triggerThreshold);
    }

    /**
     * Write out a line of sample data.
     *
     * @param writer	output stream
     * @param num		number of this sample
     * @param sampleId	ID of this sample
     * @param growth	growth data
     * @param badFlag	"Y" if bad, "" if good, "?" if questionable
     */
    private void writeSampleData(PrintWriter writer, int num, SampleId sampleId, GrowthData growth, String badFlag) {
        int fixed = growth.getFixCount();
        int unfixed = growth.size() - growth.getFixCount();
        String fixString;
        if (fixed == 0)
            fixString = "\t";
        else
            fixString = String.format("%d\t%d", fixed, unfixed);
        writer.format("%d\t%s\t%s\t%1.9f\t%s\t%s\t%s\t%s\t%1.9f\t%1.9f\t%s\t%s\t%s%n",
                num, growth.getOldStrain(), sampleId.toString(), growth.getProduction(),
                format(growth.getPrediction(), "%1.4f"), format(growth.getDensity(), "%1.2f"),
                badFlag, fixString, growth.getNormalizedProduction(), growth.getProductionRate(),
                growth.getOrigins(), growth.getProductionList(), growth.getGrowthList());
    }

    /**
     * This function formats a double-precision to a string.  If the value is NaN, it returns the empty string.
     *
     * @param num		number to format
     * @param fmt		format to apply
     */
    public static String format(double num, String fmt) {
        String retVal;
        if (Double.isNaN(num))
            retVal = "";
        else
            retVal = String.format(fmt, num);
        return retVal;
    }
}
