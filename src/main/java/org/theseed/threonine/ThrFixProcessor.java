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
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Argument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.TabbedLineReader;
import org.theseed.utils.BaseProcessor;

/**
 * This is a special-purpose script to reconcile the threonine growth data between the old master file and
 * the new strain file.
 *
 * Each row of the new strain file is identified in the old file by a strain name, an IPTG flag, and a time
 * stamp.  These three information items can be translated into a unique sample ID.  All rows that map to the
 * same sample ID will be averaged together to determine the correct growth and production data.  There is a
 * good/bad flag and an ID number carried over from the new file.  We then compute the normalized production
 * (raw / optical density) and the growth rate (production / time).
 *
 * The positional parameters are the name of the old file, the name of the new file, and the name of a file
 * to contain the choices for the parts of the strain name.
 *
 * @author Bruce Parrello
 *
 */
public class ThrFixProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(ThrFixProcessor.class);
    /** map from old strain IDs to new strain IDs */
    private Map<String, String> strainMap;
    /** set of bad sample IDs */
    private Set<String> badSamples;
    /** accumulated growth data for each sample ID */
    private SortedMap<SampleId, GrowthData> growthMap;
    /** strain fragment sets */
    private List<Set<String>> choices;

    // COMMAND-LINE OPTIONS

    /** old strain data file */
    @Argument(index = 0, metaVar = "oldStrains.tbl", usage = "old strain information file", required = true)
    private File oldFile;

    /** new strain data file */
    @Argument(index = 1, metaVar = "newStrains.tbl", usage = "new strain information file", required = true)
    private File newFile;

    /** choices output file */
    @Argument(index = 2, metaVar = "choices.tbl", usage = "choices output file", required = true)
    private File choiceFile;

    @Override
    protected void setDefaults() {
    }

     @Override
    protected boolean validateParms() throws IOException {
        // Verify the input files.
        if (! this.oldFile.canRead())
            throw new FileNotFoundException("Old strain input file " + this.oldFile + " is not found or unreadable.");
        if (! this.newFile.canRead())
            throw new FileNotFoundException("New strain input file " + this.newFile + " is not found or unreadable.");
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Initialize the choices tracker.
        this.choices = new ArrayList<Set<String>>();
        // Initialize the bad-samples set.
        this.badSamples = new HashSet<String>(100);
        // Initialize the master map of sample IDs to growth data.
        this.growthMap = new TreeMap<SampleId, GrowthData>();
        // Initialize the strain ID map.
        this.strainMap = new HashMap<String, String>(3500);
        // Read the old-strain file to build the strain map.
        log.info("Reading new sample input file {}.", this.newFile);
        try (TabbedLineReader newStream = new TabbedLineReader(this.newFile)) {
            int strainCol = newStream.findField("Strain");
            int sampleCol = newStream.findField("Sample");
            int badCol = newStream.findField("bad");
            for (TabbedLineReader.Line line : newStream) {
                // Get the old strain ID.  Note we have to case-fold.
                String strain = line.get(strainCol).toLowerCase();
                // Get the sample ID and the new strain ID.
                SampleId sampleId = new SampleId(line.get(sampleCol));
                String newStrain = sampleId.toStrain();
                // Fix the thrabc error in the strain ID.
                this.strainMap.put(strain, newStrain);
                // Now check for a bad-sample flag.
                if (line.getFancyFlag(badCol))
                    this.badSamples.add(sampleId.toString());
                else {
                    // Here we have a good sample.  Save its fragments in the choice array.
                    String[] fragments = sampleId.getBaseFragments();
                    while (this.choices.size() <= fragments.length)
                        this.choices.add(new TreeSet<String>());
                    for (int i = 0; i < fragments.length; i++) {
                        this.choices.get(i).add(fragments[i]);
                    }
                    int idx = fragments.length;
                    this.choices.get(idx).addAll(sampleId.getDeletes());
                }
            }
        }
        log.info("{} strains found, {} bad samples identified.", this.strainMap.size(), this.badSamples.size());
        // Now write out the choices.  Note that for everything but the deletes, we require one less column than
        // the number of choices.
        int colCount = 1;
        try (PrintWriter printer = new PrintWriter(this.choiceFile)) {
            for (Set<String> options : this.choices) {
                printer.println(StringUtils.join(options, ", "));
                colCount += options.size() - 1;
            }
        }
        log.info("{} columns required for training set.", colCount);
        // Now we read the old strain file and create the output.
        log.info("Reading old sample input file {}.", this.oldFile);
        // If the flag field is blank we use the saved value.
        boolean iptgFlag = false;
        // Here we'll count rows we skipped due to missing numbers or untranslatable strains.
        int badNumRows = 0;
        int badStrainRows = 0;
        int keptRows = 0;
        int zeroProdRows = 0;
        // Now loop through the file.
        try (TabbedLineReader oldStream = new TabbedLineReader(this.oldFile)) {
            int strainCol = oldStream.findField("Strain");
            int iptgCol = oldStream.findField("iptg");
            int timeCol = oldStream.findField("time");
            int prodCol = oldStream.findField("Thr");
            int densCol = oldStream.findField("Growth");
            for (TabbedLineReader.Line line : oldStream) {
                // Verify that this line has numbers in the growth and production columns.
                if (line.isEmpty(prodCol) || line.isEmpty(densCol))
                    badNumRows++;
                else {
                    // Here we have good data.  Get the
                    String strain = line.get(strainCol);
                    String newStrain = this.strainMap.get(strain.toLowerCase());
                    if (newStrain == null)
                        badStrainRows++;
                    else {
                        // Compute the IPTG flag.  Note an empty value simply keeps the old value.
                        if (! line.isEmpty(iptgCol))
                            iptgFlag = line.getFancyFlag(iptgCol);
                        // Now get the time stamp.
                        final double time;
                        final String timeString;
                        if (line.isEmpty(timeCol)) {
                            time = Double.NaN;
                            timeString = "ML";
                        } else {
                            time = line.getDouble(timeCol);
                            timeString = StringUtils.replaceChars(line.get(timeCol), '.', 'p');
                        }
                        // Finally, get the production and density.
                        double prod = line.getDouble(prodCol);
                        double dens = line.getDouble(densCol);
                        if (prod == 0.0)
                            zeroProdRows++;
                        // Store this row in the sample map.
                        SampleId sampleId = new SampleId(newStrain + "_" + (iptgFlag ? "I" : "0") + "_" + timeString + "_M1");
                        GrowthData growth = this.growthMap.computeIfAbsent(sampleId, x -> new GrowthData(strain, time));
                        growth.merge(prod, dens);
                        // Denote we've kept this row.
                        keptRows++;
                    }
                }
            }
        }
        log.info("{} rows were missing growth or production numbers, {} had invalid strains, and {} were kept.  {} total samples.",
                badNumRows, badStrainRows, keptRows, this.growthMap.size());
        log.info("{} kept rows had no production.", zeroProdRows);
        log.info("Producing output.");
        System.out.println("num\told_strain\tsample\tthr_production\tgrowth\tbad\tthr_normalized\tthr_rate");
        int num = 0;
        for (Map.Entry<SampleId, GrowthData> sampleEntry : this.growthMap.entrySet()) {
            String sampleId = sampleEntry.getKey().toString();
            GrowthData growth = sampleEntry.getValue();
            String badFlag = (this.badSamples.contains(sampleId) ? "Y" : "");
            num++;
            System.out.format("%d\t%s\t%s\t%1.9f\t%1.2f\t%s\t%1.9f\t%1.9f%n",
                    num, growth.getOldStrain(), sampleId, growth.getProduction(), growth.getDensity(),
                    badFlag, growth.getNormalizedProduction(), growth.getProductionRate());
        }
    }

}
