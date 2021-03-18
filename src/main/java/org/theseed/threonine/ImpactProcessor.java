/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.LineReader;
import org.theseed.io.TabbedLineReader;
import org.theseed.samples.SampleId;
import org.theseed.utils.BaseProcessor;

/**
 * This command will estimate the impact of individual parameters using a prediction result file.  For each sample ID segment,
 * it will compute the mean prediction for each value of the segment.  These predictions can be compared to estimate impact.
 *
 * The input file is taken from the standard input.  The sample ID must be in the first column.
 *
 * The report will be produced on the standard output.
 *
 * The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	show more detailed log messages
 * -i	override for input file (if not STDIN)
 * -c	index (1-based) or name of the column containing the predicted value (the default is "predicted")
 *
 * --choices	choices file containing the choices for each strain segment (the default is "choices.tbl" in the current directory)
 *
 * @author Bruce Parrello
 *
 */
public class ImpactProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(ImpactProcessor.class);
    /** list of segment maps, for each segment we map all values to a stats object; there is space in here for the deletes, but
     *  they are not kept in here */
    private List<Map<String, SummaryStatistics>> masterCounts;
    /** map of deleted-protein stats */
    private Map<String, SummaryStatistics> deletedCounts;
    /** map of kept-protein stats */
    private Map<String, SummaryStatistics> keptCounts;
    /** set of deletable proteins */
    private Set<String> deletables;
    /** input file reader */
    private TabbedLineReader inStream;
    /** prediction column index */
    private int predIdx;

    // COMMAND-LINE OPTIONS

    /** input file name (if not STDIN) */
    @Option(name = "--input", aliases = { "-i" }, metaVar = "predictions.tbl", usage = "input file (if not STDIN)")
    private File inFile;

    /** choices file */
    @Option(name = "--choices", metaVar = "choices.tbl", usage = "choice file for strain IDs")
    private File choiceFile;

    /** prediction column */
    @Option(name = "--col", aliases = { "-c" }, usage = "index (1-based) or name of prediction value column")
    private String predCol;

    @Override
    protected void setDefaults() {
        this.inFile = null;
        this.predCol = "predicted";
        this.choiceFile = new File(System.getProperty("user.dir"), "choices.tbl");
    }

    @Override
    protected boolean validateParms() throws IOException {
        // Verify the choice file.
        if (! this.choiceFile.canRead())
            throw new FileNotFoundException("Choices file " + this.choiceFile + " is not found or unreadable.");
        // Handle the input file.
        if (this.inFile == null) {
            this.inStream = new TabbedLineReader(System.in);
            log.info("Input will be read from standard input.");
        } else {
            if (! this.inFile.canRead())
                throw new FileNotFoundException("Input file " + this.inFile + " not found or unreadable.");
            this.inStream = new TabbedLineReader(this.inFile);
            log.info("Input will be read from {}.", this.inFile);
        }
        // Compute the prediction column index.
        this.predIdx = this.inStream.findField(this.predCol);
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Create the statistics map.
        log.info("Creating counters.");
        this.masterCounts = new ArrayList<Map<String, SummaryStatistics>>(SampleId.NORMAL_SIZE);
        // Fill the master count list with tree-maps.  We use tree maps because the number of choices is small.
        for (int i = 0; i < SampleId.NORMAL_SIZE; i++)
            this.masterCounts.add(new TreeMap<String, SummaryStatistics>());
        // The deletes are different, because they are a single string describing a set.  We find the delete information
        // in the choices file and create maps for them.
        try (LineReader choiceReader = new LineReader(this.choiceFile)) {
            for (int i = 0; i < SampleId.DELETE_COL; i++)
                choiceReader.next();
            String deleteList = choiceReader.next();
            this.deletables = Arrays.stream(StringUtils.split(deleteList, ", ")).collect(Collectors.toCollection(TreeSet::new));
            log.info("{} deletable proteins identified.", this.deletables.size());
        }
        this.deletedCounts = new HashMap<String, SummaryStatistics>(this.deletables.size());
        this.keptCounts = new HashMap<String, SummaryStatistics>(this.deletables.size());
        for (String deletable : deletables) {
            this.deletedCounts.put(deletable, new SummaryStatistics());
            this.keptCounts.put(deletable, new SummaryStatistics());
        }
        // Loop through the input.
        log.info("Processing input.");
        int count = 0;
        for (TabbedLineReader.Line line : this.inStream) {
            SampleId sample = new SampleId(line.get(0));
            double prediction = line.getDouble(this.predIdx);
            // Loop through the fragments of the sample ID, skipping over the delete fragment.
            for (int i = 0; i < SampleId.NORMAL_SIZE; i++) {
                if (i != SampleId.DELETE_COL) {
                    String value = sample.getFragment(i);
                    Map<String, SummaryStatistics> map = this.masterCounts.get(i);
                    // Count this value.
                    SummaryStatistics stats = map.computeIfAbsent(value, x -> new SummaryStatistics());
                    stats.addValue(prediction);;
                }
            }
            // Now process the delete fragment.
            Set<String> deleted = sample.getDeletes();
            for (String deletable : this.deletables) {
                if (deleted.contains(deletable))
                    this.deletedCounts.get(deletable).addValue(prediction);
                else
                    this.keptCounts.get(deletable).addValue(prediction);
            }
            // Count this line.
            count++;
            if (log.isInfoEnabled() && count % 5000 == 0)
                log.info("{} predictions processed.", count);
        }
        log.info("End of file. {} total predictions.", count);
        this.inStream.close();
        // Now we write the report.  First the header.
        System.out.println("value\tmin\tmean\tmax\tstdev\tcount");
        // Now the standard values.
        for (int i = 0; i < SampleId.NORMAL_SIZE; i++) {
            if (i != SampleId.DELETE_COL) {
                System.out.println();
                for (Map.Entry<String, SummaryStatistics> valueEntry : this.masterCounts.get(i).entrySet()) {
                    SummaryStatistics stats = valueEntry.getValue();
                    String value = valueEntry.getKey();
                    writeValueStats(stats, value);
                }
            }
        }
        // Finally the deletes.
        System.out.println();
        for (String deletable : this.deletables) {
            writeValueStats(this.keptCounts.get(deletable), "kept " + deletable);
            writeValueStats(this.deletedCounts.get(deletable), "del " + deletable);
        }
        System.out.close();
    }

    /**
     * Write the statistics for a value.
     *
     * @param stats		statistics to write
     * @param value		relevant value
     */
    public void writeValueStats(SummaryStatistics stats, String value) {
        System.out.format("%s\t%11.4f\t%11.4f\t%11.4f\t%11.4f\t%d%n", value,
                stats.getMin(), stats.getMean(), stats.getMax(), stats.getStandardDeviation(),
                stats.getN());
    }

}
