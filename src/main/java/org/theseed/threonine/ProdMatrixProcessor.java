/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.counters.CountMap;
import org.theseed.io.TabbedLineReader;
import org.theseed.reports.ProdMatrixReporter;
import org.theseed.samples.SampleId;
import org.theseed.utils.BaseProcessor;
import org.theseed.utils.ParseFailureException;

/**
 *
/**
 * This command creates a matrix of threonine production values.  The different insert combinations will be represented as columns and the
 * different delete combinations are rows.  The other parameters (strain, IPTG, time, etc) will be fixed.
 *
 * The algorithm starts by isolating the desired samples.  A certain production level is specified as a cutoff, with levels above the cutoff
 * considered high-production.  For each insert protein and each delete protein, we count the number of samples with that protein specified
 * that are above the cutoff.  The proteins are then sorted from the most high-producing samples to the fewest.  The set of inserted or
 * deleted proteins is then converted to a binary number with the digit positions corresponding to the proteins in sort order (most high-producing
 * is most significant).  This binary number is used to sort the row and column headers.
 *
 * The positional parameters are the name of the big production table and the name of the output file.
 *
 * This big production table is tab-delimited, with the sample ID in the column "sample", the production level in "thr_production", and a quality flag
 * in "bad".
 *
 * THe command-line options are as follows:
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -o	output file name (if not STDOUT)
 *
 * --strain		the strain ID for the samples to process
 * --operon		the threonine operon style for the samples to process
 * --asd		the ASD mode for the samples to process
 * --time		the time point for the samples to process
 * --noIptg		if specified, only IPTG-negative samples will be processed; normally only IPTG-positive samples will be processed
 * --cutoff		minimum output value for high-performing strains
 * --outFormat	output report format
 *
 * @author Bruce Parrello
 *
 */
public class ProdMatrixProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(ProdMatrixProcessor.class);
    /** map of insert proteins to number of high-performing samples */
    private CountMap<String> insertCounts;
    /** map of delete proteins to number of high-performing deletes */
    private CountMap<String> deleteCounts;
    /** map of sample IDs to production levels */
    private Map<SampleId, Double> prodMap;
    /** set of insert strings */
    private Set<String> insertsFound;
    /** set of delete strings */
    private Set<String> deletesFound;
    /** output report formatter */
    private ProdMatrixReporter reporter;

    // COMMAND-LINE OPTIONS

    /** output report format */
    @Option(name = "--outFormat", usage = "output format")
    private ProdMatrixReporter.Type outFormat;

    /** strain ID to process */
    @Option(name = "--strain", metaVar = "7", usage = "ID of the strain whose samples should be processed")
    private String strainFilter;

    /** threonine operon style to process */
    @Option(name = "--operon", metaVar = "TasdA", usage = "ID of the threonine operon style whose samples should be processed")
    private String operonFilter;

    /** ASD mode to process */
    @Option(name = "--asd", metaVar = "asdO", usage = "ID of the ASD mode whose samples should be processed")
    private String asdFilter;

    /** time point to process */
    @Option(name = "--time", metaVar = "24", usage = "time point whose samples should be processed")
    private double timeFilter;

    /** IPTG-negative mode */
    @Option(name = "--noIptg", usage = "if specified, IPTG-negative samples will be processed instead of IPTG-positive")
    private boolean iptgNegative;

    /** cutoff level */
    @Option(name = "--cutoff", metaVar = "2.0", usage = "cutoff level for high-performing samples")
    private double cutoffLevel;

    /** name of the input big production table */
    @Argument(index = 0, metaVar = "big_production_table.txt", usage = "input big production table", required = true)
    private File inFile;

    /** name of the output file */
    @Argument(index = 1, metaVar = "outFile.txt", usage = "output report name", required = true)
    private File outFile;

    @Override
    protected void setDefaults() {
        this.strainFilter = "M";
        this.operonFilter = "TA1";
        this.asdFilter = "asdO";
        this.timeFilter = 24.0;
        this.iptgNegative = false;
        this.cutoffLevel = 1.2;
        this.outFormat = ProdMatrixReporter.Type.TEXT;
    }

    @Override
    protected boolean validateParms() throws IOException, ParseFailureException {
        if (this.timeFilter < 0.0)
            throw new ParseFailureException("Time point cannot be negative.");
        if (this.cutoffLevel <= 0.0)
            throw new ParseFailureException("Cutoff level must be greater than 0.0.");
        if (! this.inFile.canRead())
            throw new FileNotFoundException("Input file " + this.inFile + " is not found or unreadable.");
        // Create the reporter.
        this.reporter = this.outFormat.create(this.outFile);
        // Initialize the maps and things.
        this.deleteCounts = new CountMap<String>();
        this.insertCounts = new CountMap<String>();
        this.insertsFound = new HashSet<String>();
        this.deletesFound = new HashSet<String>();
        this.prodMap = new HashMap<SampleId, Double>(6000);
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Open the input file.
        try (TabbedLineReader inStream = new TabbedLineReader(this.inFile)) {
            // Find the useful data columns.
            int sampleCol = inStream.findField("sample");
            int prodCol = inStream.findField("thr_production");
            int badCol = inStream.findField("bad");
            // Initialize the reporter's data structures.
            this.reporter.openReport();
            // We will count the number of high samples, the number of rejected samples, the number of bad samples, and the number of samples kept.
            int keptCount = 0;
            int totalCount = 0;
            int highCount = 0;
            int badCount = 0;
            int skipCount = 0;
            // Loop through the input.
            log.info("Reading input file.");
            for (TabbedLineReader.Line line : inStream) {
                totalCount++;
                // Skip over bad samples.
                if (! line.get(badCol).isEmpty())
                    badCount++;
                else {
                    SampleId sample = new SampleId(line.get(sampleCol));
                    // Check the filtering.
                    if (! this.keepSample(sample))
                        skipCount++;
                    else {
                        keptCount++;
                        // Get the production value.
                        double prodVal = line.getDouble(prodCol);
                        boolean highFlag = false;
                        if (prodVal >= this.cutoffLevel) {
                            highFlag = true;
                            highCount++;
                        }
                        // Get the insert and delete sets.  We need to record them as being part of the
                        // input.
                        String insertString = sample.getFragment(SampleId.INSERT_COL);
                        this.processString('-', insertString, this.insertsFound, this.insertCounts, highFlag);
                        String deleteString = sample.getFragment(SampleId.DELETE_COL);
                        this.processString('D', deleteString, this.deletesFound, this.deleteCounts, highFlag);
                        // Save the production level.
                        this.prodMap.put(sample, prodVal);
                    }
                }
            }
            log.info("{} samples processed, {} were bad, {} were filtered out, {} were kept.  {} were high production.",
                    totalCount, badCount, skipCount, keptCount, highCount);
            // Sort the inserts and deletes.
            List<String> insertProts = sortProteins(this.insertCounts);
            this.reporter.setColumns(insertProts, this.insertsFound);
            List<String> deleteProts = sortProteins(this.deleteCounts);
            this.reporter.setRows(deleteProts, this.deletesFound);
            // Write the report.
            log.info("Writing output.");
            this.reporter.closeReport(this.prodMap);
        } finally {
            this.reporter.close();
        }
    }

    /**
     * @return the sorted list of proteins from the count map
     *
     * @param countMap	map from each protein to the number of times it is high-producing
     */
    public List<String> sortProteins(CountMap<String> countMap) {
        return countMap.sortedCounts().stream().map(x -> x.getKey()).collect(Collectors.toList());
    }

    /**
     * Process a protein fragment (either inserts or deletes).  The protein string is put into the found set
     * and then separated into its components.  The components are then counted in the count map.  We count 0 if
     * the production value is low and 1 if it is high.
     *
     * @param delim			delimiter between proteins
     * @param protString	protein list string
     * @param protsFound	set of proteins found so far
     * @param protMap		count map of high-production samples per protein
     * @param highFlag		TRUE if this is a high-production sample, else FALSE
     *
     * @return TRUE if the sample is high-production, else FALSE
     */
    private void processString(char delim, String protString, Set<String> protsFound,
            CountMap<String> protMap, boolean highFlag) {
        Set<String> prots = ProdMatrixReporter.computeProts(delim, protString);
        int count = (highFlag ? 1 : 0);
        protsFound.add(protString);
        for (String prot : prots)
            protMap.count(prot, count);
    }

    /**
     * @return TRUE if we are interested in this sample, else FALSE
     *
     * @param sample	ID of the sample to check
     */
    private boolean keepSample(SampleId sample) {
        boolean retVal = (sample.getFragment(SampleId.STRAIN_COL).contentEquals(this.strainFilter)
                && sample.getFragment(SampleId.OPERON_COL).contentEquals(this.operonFilter)
                && sample.getFragment(SampleId.ASD_COL).contentEquals(this.asdFilter)
                && sample.getTimePoint() == this.timeFilter && sample.isIPTG() != this.iptgNegative);
        return retVal;
    }

}
