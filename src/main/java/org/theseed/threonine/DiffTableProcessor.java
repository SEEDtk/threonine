/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.TabbedLineReader;
import org.theseed.reports.SampleDiffWorkbook;
import org.theseed.samples.SampleDiffTable;
import org.theseed.samples.SampleId;
import org.theseed.utils.BaseProcessor;
import org.theseed.utils.ParseFailureException;
import org.theseed.utils.SetUtils;

/**
 * This command creates a spreadsheet that illustrates how individual sample features affect production.
 *
 * The positional parameters are the name of the input file containing the production values and the file name
 * for the output spreadsheet.  The input file should be tab-delimited with headers.  The sample IDs are taken
 * from a column named "Sample", the output from a column named "thr_production", and the quality flag from a
 * column named "bad".
 *
 * The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 *
 * --all		if specified, questionable samples will be included
 * --time		if specified, a time point; only the specified time point will be included
 * --strains	comma-delimimted list of strains to use; the default is "7,M";
 * --iptg		if specified, only IPTG-positive samples will be included
 * --min		minimum number of entries in a row required to output the row (default 1)
 *
 * @author Bruce Parrello
 *
 */
public class DiffTableProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(DiffTableProcessor.class);
    /** difference tables */
    private EnumMap<SampleDiffTable.Category, SampleDiffTable> tables;
    /** set of acceptable strains */
    private Set<String> strainList;

    // COMMAND-LINE OPTIONS

    /** all-samples flag */
    @Option(name = "--all", usage = "if specified, bad and questionable samples will be included")
    private boolean allFlag;

    /** desired time point */
    @Option(name = "--time", metaVar = "24.0", usage = "if specified, a time point to which the output should be restricted")
    private double timeFilter;

    /** list of acceptable strains */
    @Option(name = "--strains", metaVar = "M", usage = "comma-delimited list of acceptable strains")
    private String strainFilter;

    /** IPTG-only flag */
    @Option(name = "--iptg", usage = "if specified, only IPTG-positive samples will be included")
    private boolean iptgOnly;

    /** minimum number of entries per row */
    @Option(name = "--min", metaVar = "3", usage = "minimum number of entries per row to qualify for output")
    private int minWidth;

    /** input sample file */
    @Argument(index = 0, metaVar = "big_production_master.txt", usage = "input file name", required = true)
    private File inFile;

    /** output excel file */
    @Argument(index = 1, metaVar = "diffTables.xlsx", usage = "output Excel workbook file name", required = true)
    private File outFile;

    @Override
    protected void setDefaults() {
        this.allFlag = false;
        this.timeFilter = Double.NaN;
        this.strainFilter = "7,M";
        this.iptgOnly = false;
        this.minWidth = 1;
    }

    @Override
    protected boolean validateParms() throws IOException, ParseFailureException {
        if (! this.inFile.canRead())
            throw new FileNotFoundException("Input file " + this.inFile + " is not found or unreadable.");
        if (this.minWidth < 1)
            throw new ParseFailureException("Minimum entry filter must be at least 1.");
        // Get the strain list.
        this.strainList = SetUtils.newFromArray(StringUtils.split(this.strainFilter, ','));
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Create the difference tables.
        SampleDiffTable.Category[] categories = SampleDiffTable.Category.values();
        log.info("Creating difference tables for {} categories.", categories.length);
        this.tables = new EnumMap<SampleDiffTable.Category, SampleDiffTable>(SampleDiffTable.Category.class);
        for (SampleDiffTable.Category category : categories)
            this.tables.put(category, category.create());
        // Now we read in the samples.  Each sample is put in ALL of the difference tables.
        log.info("Reading sample data from {}.", this.inFile);
        int linesIn = 0;
        int linesSkipped = 0;
        try (TabbedLineReader inStream = new TabbedLineReader(this.inFile)) {
            int sampIdx = inStream.findField("sample");
            int prodIdx = inStream.findField("thr_production");
            int badIdx = inStream.findField("bad");
            for (TabbedLineReader.Line line : inStream) {
                linesIn++;
                if (! this.allFlag && line.get(badIdx).contentEquals("?")) {
                    // Here we have a bad or questionable sample.
                    linesSkipped++;
                } else {
                    // Get the sample data.
                    String sampleId = line.get(sampIdx);
                    double production = line.getDouble(prodIdx);
                    // Parse the sample ID.
                    SampleId sample = new SampleId(sampleId);
                    // Do time and strain filtering.
                    if (! Double.isNaN(this.timeFilter) && sample.getTimePoint() != this.timeFilter)
                        linesSkipped++;
                    else if (! this.strainList.contains(sample.getFragment(SampleId.STRAIN_COL)))
                        linesSkipped++;
                    else if (this.iptgOnly && ! sample.isIPTG())
                        linesSkipped++;
                    else {
                        // Put the sample in the tables.
                        for (SampleDiffTable table : this.tables.values())
                            table.addSample(sample, production);
                    }
                }
            }
        }
        log.info("{} samples read, {} skipped.", linesIn, linesSkipped);
        // Now write out the spreadsheet.
        SampleDiffWorkbook output = new SampleDiffWorkbook(this.outFile, this.minWidth);
        for (Map.Entry<SampleDiffTable.Category, SampleDiffTable> tableEntry : this.tables.entrySet()) {
            SampleDiffTable table = tableEntry.getValue();
            if (table.getChoices().size() > 1) {
                String name = tableEntry.getKey().getDescription();
                log.info("Producing worksheet for {}.", name);
                output.createSheet(name, table);
            }
        }
        log.info("Writing workbook to {}.", this.outFile);
        output.save();
    }

}
