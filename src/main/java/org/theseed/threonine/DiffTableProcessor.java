/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

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

/**
 * This command creates a spreadsheet that illustrates how individual sample features affect production.
 *
 * The positional parameters are the name of the input file containing the production values and the file name
 * for the output spreadsheet.  The input file should be tab-delimited with headers.  The sample IDs are taken
 * from a column named "Sample", the output from a column named "thr_production", and the quality flag from a
 * column named "bad".  Finally, in the column named "old_strain", samples whose old name starts with "nrrl "
 * will be skipped.
 *
 * The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 *
 * --all	if specified, all samples will be processed; even questionable ones
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

    // COMMAND-LINE OPTIONS

    /** all-samples flag */
    @Option(name = "--all", usage = "if specified, bad and questionable samples will be included")
    private boolean allFlag;

    /** input sample file */
    @Argument(index = 0, metaVar = "big_production_master.txt", usage = "input file name", required = true)
    private File inFile;

    /** output excel file */
    @Argument(index = 1, metaVar = "diffTables.xlsx", usage = "output Excel workbook file name", required = true)
    private File outFile;

    @Override
    protected void setDefaults() {
        this.allFlag = false;
    }

    @Override
    protected boolean validateParms() throws IOException, ParseFailureException {
        if (! this.inFile.canRead())
            throw new FileNotFoundException("Input file " + this.inFile + " is not found or unreadable.");
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
            int oldIdx = inStream.findField("old_strain");
            int sampIdx = inStream.findField("sample");
            int prodIdx = inStream.findField("thr_production");
            int badIdx = inStream.findField("bad");
            for (TabbedLineReader.Line line : inStream) {
                linesIn++;
                String oldName = line.get(oldIdx);
                if (StringUtils.startsWith(oldName, "nrrl ")) {
                    // Here we have an unsupported strain.
                    linesSkipped++;
                } else if (! this.allFlag && ! line.get(badIdx).isEmpty()) {
                    // Here we have a bad or questionable sample.
                    linesSkipped++;
                } else {
                    // Get the sample data.
                    String sampleId = line.get(sampIdx);
                    double production = line.getDouble(prodIdx);
                    // Parse the sample ID.
                    SampleId sample = new SampleId(sampleId);
                    // Put the sample in the tables.
                    for (SampleDiffTable table : this.tables.values())
                        table.addSample(sample, production);
                }
            }
        }
        log.info("{} samples read, {} skipped.", linesIn, linesSkipped);
        // Now write out the spreadsheet.
        SampleDiffWorkbook output = new SampleDiffWorkbook(this.outFile);
        for (Map.Entry<SampleDiffTable.Category, SampleDiffTable> tableEntry : this.tables.entrySet()) {
            String name = tableEntry.getKey().getDescription();
            log.info("Producing worksheet for {}.", name);
            output.createSheet(name, tableEntry.getValue());
        }
        log.info("Writing workbook to {}.", this.outFile);
        output.save();
    }

}
