/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.TabbedLineReader;
import org.theseed.reports.ThrProductionFormatter;
import org.theseed.utils.BaseProcessor;

/**
 * This command formats the threonine production data.  The data comes in on the standard input, in tab-delimited
 * format.  The following columns are used
 *
 * 	sample			sample ID
 * 	thr_production	threonine production
 * 	growth			optical density
 *
 * There is also a required "choices.tbl" file that describes the labels for each of the fields in the strain name.
 * The records in this file correspond to the fields in the strain ID.  The last record represents the deleted genes,
 * which get special treatment.  The possible field values are listed in comma-separated form.  Values of "0" or "000"
 * are treated as empty cases.
 *
 * The positional parameter is the name of the output file.
 *
 * The command-line options are as follows.
 *
 * --input		input file (if not the standard input)
 * --choices	name of the choices file (default is "choices.tbl" in the current directory)
 * --format		format for the output
 *
 * @author Bruce Parrello
 *
 */
public class ProdFormatProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(ProdFormatProcessor.class);
    /** input stream */
    private InputStream reader;

    // COMMAND-LINE OPTIONS

    /* input file (if not STDIN) */
    @Option(name = "--input", aliases = { "-i", "--in" }, metaVar = "inFile.tbl", usage = "input file (if not STDIN)")
    private File inFile;

    /** field choices definition file */
    @Option(name = "--choices", metaVar = "choices.txt", usage = "file containing permissible choices for sample specs")
    private File choiceFile;

    /** format of the output */
    @Option(name = "--format", usage = "output format")
    private ThrProductionFormatter.Type format;

    /** output file */
    @Argument(index = 0, metaVar = "outFile.csv", usage = "output file")
    private File outFile;

    @Override
    protected void setDefaults() {
        this.inFile = null;
        this.choiceFile = new File(System.getProperty("user.dir"), "choices.tbl");
        this.format = ThrProductionFormatter.Type.TABLE;
    }

    @Override
    protected boolean validateParms() throws IOException {
        if (this.inFile == null) {
            this.reader = System.in;
            log.info("Production data will be read from the standard input.");
        } else if (! this.inFile.canRead())
            throw new FileNotFoundException("Input file " + this.inFile + " is not found or unreadable.");
        else {
            this.reader = new FileInputStream(this.inFile);
            log.info("Production data will be read from {}.", this.inFile);
        }
        if (! this.choiceFile.canRead())
            throw new FileNotFoundException("Choices file " + this.choiceFile + " is not found or unreadable.");
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Open the output object and the input file.
        try (TabbedLineReader reader = new TabbedLineReader(this.reader);
                ThrProductionFormatter writer = this.format.create(this.outFile)) {
            // Locate the important input columns.
            int sampleCol = reader.findField("sample");
            int prodCol = reader.findField("thr_production");
            int growthCol = reader.findField("growth");
            int badCol = reader.findField("bad");
            // Start the report.
            writer.initialize(this.choiceFile);
            log.info("Reading input file.");
            int count = 0;
            int badCount = 0;
            // Loop through the input.
            for (TabbedLineReader.Line line : reader) {
                if (line.getFlag(badCol))
                    badCount++;
                else {
                    String sampleId = line.get(sampleCol);
                    double production = line.getDouble(prodCol);
                    double density = line.getDouble(growthCol);
                    writer.writeSample(sampleId, production, density);
                    count++;
                }
            }
            log.info("{} samples written, {} bad samples skipped.", count, badCount);
        }
    }

}
