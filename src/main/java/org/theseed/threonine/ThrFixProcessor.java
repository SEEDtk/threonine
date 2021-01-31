/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.TabbedLineReader;
import org.theseed.proteins.SampleId;
import org.theseed.utils.BaseProcessor;

/**
 * This is a special-purpose script to reconcile the threonine growth data in the master file.
 *
 * Each row of the new strain file is identified in the old file by a strain name, an IPTG flag, and a time
 * stamp.  These three information items can be translated into a unique sample ID.  All rows that map to the
 * same sample ID will be averaged together to determine the correct growth and production data.  There is a
 * "Suspect" flag used to identify bad samples.  There are also the "experiment" and "Sample_y" columns containing
 * the original experiment ID and the well containing the sample.  Finally, we compute the normalized production
 * (raw / optical density) and the growth rate (production / time).
 *
 * The positional parameters are the name of the master file and the name of a file to contain the choices
 * for the parts of the strain name.
 *
 * The command-line options are as follows.
 *
 * -h	display command-line usagee
 * -v	display more frequent log messages
 *
 * --good	only output good samples
 * --alert	specifies a range; production values with a higher spread in values than
 * 			the specified range are flagged as questionable (the default is 0.3)
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

    // COMMAND-LINE OPTIONS

    /** only output good samples */
    @Option(name = "--good", usage = "only output good samples")
    private boolean goodFlag;

    /** maximum production range to be considered reliable */
    @Option(name = "--alert", metaVar = "0.1", usage = "maximum reliable production range")
    private double alertRange;

    /** old strain data file */
    @Argument(index = 0, metaVar = "oldStrains.tbl", usage = "old strain information file", required = true)
    private File oldFile;

    /** choices output file */
    @Argument(index = 1, metaVar = "choices.tbl", usage = "choices output file", required = true)
    private File choiceFile;

    @Override
    protected void setDefaults() {
        this.goodFlag = false;
        this.alertRange = 0.3;
    }

     @Override
    protected boolean validateParms() throws IOException {
        // Verify the input files.
        if (! this.oldFile.canRead())
            throw new FileNotFoundException("Old strain input file " + this.oldFile + " is not found or unreadable.");
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
        // Here we'll count rows we skipped due to missing numbers or untranslatable strains.
        int badNumRows = 0;
        int badSampleRows = 0;
        int badStrainRows = 0;
        int keptRows = 0;
        int zeroProdRows = 0;
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
                    // Compute the IPTG flag.  Note an empty value simply keeps the old value.
                    if (! line.isEmpty(iptgCol))
                        iptgFlag = line.getFancyFlag(iptgCol);
                    // Compute the time point.
                    double time;
                    if (line.isEmpty(timeCol))
                        time = Double.NaN;
                    else
                        time = line.getDouble(timeCol);
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
                    } else {
                        // Update the choices.
                        String[] strainData = sample.getBaseFragments();
                        for (int i = 0; i < strainData.length; i++)
                            this.choices.get(i).add(strainData[i]);
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
                        growth.merge(prod, dens, line.get(expCol), line.get(wellCol));
                    }
                }
            }
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
        log.info("{} rows had improperly-formatted strain names.", badStrainRows);
        log.info("{} rows were missing growth or production numbers, {} input rows were suspect, and {} were good.",
                badNumRows, badSampleRows, keptRows);
        log.info("{} good rows had no production.", zeroProdRows);
        log.info("Producing output.");
        System.out.println("num\told_strain\tsample\tthr_production\tgrowth\tbad\tthr_normalized\tthr_rate\torigins\traw_productions");
        // Write the good data.
        int num = 0;
        int qCount = 0;
        for (Map.Entry<SampleId, GrowthData> sampleEntry : this.growthMap.entrySet()) {
            SampleId sampleId = sampleEntry.getKey();
            GrowthData growth = sampleEntry.getValue();
            num++;
            double range = growth.getProductionRange();
            String qFlag = "";
            if (range > this.alertRange) {
                qFlag = "?";
                qCount++;
            }
            writeSampleData(num, sampleId, growth, qFlag);
        }
        log.info("{} good samples output, {} were questionable.", num, qCount);
        // Write the bad data if the user wants it.
        if (! this.goodFlag) {
            int oldNum = num;
            for (Map.Entry<SampleId, GrowthData> sampleEntry : this.badGrowthMap.entrySet()) {
                SampleId sampleId = sampleEntry.getKey();
                if (this.growthMap.containsKey(sampleId)) {
                    GrowthData growth = sampleEntry.getValue();
                    num++;
                    writeSampleData(num, sampleId, growth, "Y");
                }
            }
            log.info("{} bad samples output.", num - oldNum);
        }
    }

    /**
     * Write out a line of sample data.
     *
     * @param num		number of this sample
     * @param sampleId	ID of this sample
     * @param growth	growth data
     * @param badFlag	"Y" if bad, "" if good, "?" if questionable
     */
    private void writeSampleData(int num, SampleId sampleId, GrowthData growth, String badFlag) {
        System.out.format("%d\t%s\t%s\t%1.9f\t%1.2f\t%s\t%1.9f\t%1.9f\t%s\t%s%n",
                num, growth.getOldStrain(), sampleId.toString(), growth.getProduction(), growth.getDensity(),
                badFlag, growth.getNormalizedProduction(), growth.getProductionRate(), growth.getOrigins(),
                growth.getProductionList());
    }

}
