/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.TabbedLineReader;
import org.theseed.samples.SampleId;
import org.theseed.utils.BaseProcessor;
import org.theseed.utils.FloatList;
import org.theseed.utils.ParseFailureException;
import org.theseed.utils.SetUtils;

/**
 * This program reads a big production table on the standard input and extracts records with certain characteristics.
 * These are provided via command-line options.
 *
 * The input file is tab-delimited with headers.  The key columns are "sample", which contains the sample ID, "bad", which
 * contains "?" for questionable samples and "Y" for bad ones, and "origins", which contains a list of the original sample
 * sets and wells.  This last is used to restrict the output to certain runs.
 *
 * The output file will have an identical format with fewer records.
 *
 * The command-line options are as follows:
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -i	name of input file (if not standard input)
 * -x	comma-delimited list of sample sets to exclude from output (default none)
 * -n	comma-delimited list of sample sets to include in output (default all)
 *
 * --strains	comma-delimited list of strains to include (default "7,M")
 * --opr		comma-delimited list of operon codes to include (default all)
 * --asd		comma-delimited list of asd types to include (default all)
 * --iptgOnly	if specified, only IPTG-positive strains will be output
 * --times		comma-delimited list of time points to include (default all)
 * --pure		if specified, questionable results will be skipped
 *
 * @author Bruce Parrello
 *
 */
public class ExtractProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(ExtractProcessor.class);
    /** input stream */
    TabbedLineReader inStream;
    /** set of sample-sets to include (NULL for all) */
    private Set<String> sampleSetsIn;
    /** set of sample-sets to exclude */
    private Set<String> sampleSetsOut;
    /** set of strains to include */
    private Set<String> strainsIn;
    /** set of asd types to include (NULL for all) */
    private Set<String> asdIn;
    /** set of time points to include (NULL for all) */
    private Set<Double> timePointsIn;
    /** set of operons to include */
    private Set<String> operonsIn;

    // COMMAND-LINE OPTIONS

    /** name of the input file (if not STDIN) */
    @Option(name = "--input", aliases = { "-i" }, usage = "name of standard input file (default is standard input)")
    private File inFile;

    /** comma-delimited list of sample sets to include (NULL for all) */
    @Option(name = "--setsIn", aliases = { "-n" }, usage = "comma-delimited list of sample sets to include (default is all)")
    private String setsInList;

    /** comma-delimited list of sample sets to exclude (NULL for all) */
    @Option(name = "--setsOut", aliases = { "-x" }, usage = "comma-delimited list of sample sets to exclude (default is none)")
    private String setsOutList;

    /** comma-delimited list of strains to include */
    @Option(name = "--strains", metaVar = "M", usage = "command-delimited list of strains to extract")
    private String strainList;

    /** comma-delimited list of operon codes to include (NULL for all) */
    @Option(name = "--opr", aliases = { "--operons" }, usage = "comma-delimited list of operon codes to include (default includes all)")
    private String oprList;

    /** comma-delimited list of asd types to include (NULL for all) */
    @Option(name = "--asd", metaVar = "asdT,asdO", usage = "comma-delimited list of asd types to include (default includes all)")
    private String asdList;

    /** if specified, only IPTG positive samples will be included */
    @Option(name = "--iptgOnly", aliases = { "--iptg" }, usage = "if specified, only IPTG-positive samples will be included")
    private boolean iptgOnly;

    /** comma-delimited list of times to include (NULL for all) */
    @Option(name = "--times", metaVar = "9.0,24.0", usage = "comma-delimited list of time points to include (default includes all)")
    private String timePointsList;

    /** if specified, questionable results will be excluded */
    @Option(name = "--pure", usage = "if specified, questionable results will be excluded")
    private boolean pureFlag;

    @Override
    protected void setDefaults() {
        this.inFile = null;
        this.strainList = "7,M";
        this.setsOutList = "";
        this.timePointsList = null;
        this.pureFlag = false;
        this.iptgOnly = false;
        this.oprList = null;
        this.asdList = null;
        this.setsInList = null;
    }

    @Override
    protected boolean validateParms() throws IOException, ParseFailureException {
        // Process the exclude filter and the strain list.
        this.sampleSetsOut = SetUtils.newFromArray(StringUtils.split(this.setsOutList, ','));
        this.strainsIn = SetUtils.newFromArray(StringUtils.split(this.strainList, ','));
        // Process the time points.
        if (this.timePointsList != null) {
            FloatList pointList = new FloatList(this.timePointsList);
            this.timePointsIn = new TreeSet<Double>();
            for (double point : pointList)
                this.timePointsIn.add(point);
        }
        // Process the other filters.
        this.operonsIn = this.buildSet(this.oprList);
        this.asdIn = this.buildSet(this.asdList);
        this.sampleSetsIn = this.buildSet(this.setsInList);
        // Finally, open the input stream.
        if (this.inFile == null) {
            log.info("Reading from standard input.");
            this.inStream = new TabbedLineReader(System.in);
        } else if (! this.inFile.canRead())
            throw new FileNotFoundException("Input file " + this.inFile + " is not found or unreadable.");
        else {
            log.info("Reading from file {}.", this.inFile);
            this.inStream = new TabbedLineReader(this.inFile);
        }
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        try {
            // These will track our progress.
            int includeCount = 0;
            int totalCount = 0;
            int badCount = 0;
            int originCount = 0;
            // Find the importat column.
            int sampleCol = this.inStream.findField("sample");
            int badCol = this.inStream.findField("bad");
            int originCol = this.inStream.findField("origins");
            // Output the header line.
            System.out.println(this.inStream.header());
            // Loop through the input.
            log.info("Reading samples from input.");
            for (TabbedLineReader.Line line : this.inStream) {
                totalCount++;
                String sampleId = line.get(sampleCol);
                String badFlag = line.get(badCol);
                // Check the bad-flag first.
                if (badFlag.isEmpty() || ! this.pureFlag && badFlag.contentEquals("?"))
                    badCount++;
                else {
                    // Parse the origin.
                    String originString = line.get(originCol);
                    Set<String> origins = Arrays.stream(StringUtils.split(originString, ", "))
                            .map(x -> StringUtils.substringBefore(x, ":")).collect(Collectors.toSet());
                    if (SetUtils.containsAny(origins, this.sampleSetsOut) ||
                            (this.sampleSetsIn != null && SetUtils.containsAny(origins, this.sampleSetsIn)))
                        originCount++;
                    else if (this.isIncluded(new SampleId(sampleId))) {
                        // Here we are keeping the sample.
                        System.out.println(line.toString());
                        includeCount++;
                    }
                }
            }
            log.info("{} samples read, {} kept, {} were bad, {} were filtered by origin.", totalCount, includeCount,
                    badCount, originCount);
        } finally {
            this.inStream.close();
        }
    }

    /**
     * @return TRUE if the specified sample passes all the filters
     *
     * @param sample		sample ID
     */
    private boolean isIncluded(SampleId sample) {
        boolean retVal = true;
        if (this.iptgOnly && ! sample.isIPTG())
            retVal = false;
        else if (! SetUtils.isMember(this.asdIn, sample.getFragment(SampleId.ASD_COL)))
            retVal = false;
        else if (! SetUtils.isMember(this.strainsIn, sample.getFragment(SampleId.STRAIN_COL)))
            retVal = false;
        else if (! SetUtils.isMember(this.operonsIn, sample.getFragment(SampleId.OPERON_COL)))
            retVal = false;
        else {
            Double time = sample.getTimePoint();
            retVal = SetUtils.isMember(this.timePointsIn, time);
        }
        return retVal;
    }

    /**
     * Create a string set from an input string.  If the input string is NULL, returns NULL.
     *
     * @param string		comma-delimited list of input strings
     *
     * @return the set of strings, or NULL if the incoming string is NULL
     */
    private Set<String> buildSet(String string) {
        Set<String> retVal = null;
        if (string != null) {
            String[] items = StringUtils.split(string, ',');
            retVal = SetUtils.newFromArray(items);
        }
        return retVal;
    }

}
