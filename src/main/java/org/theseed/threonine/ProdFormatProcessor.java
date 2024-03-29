/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.basic.BaseProcessor;
import org.theseed.basic.ParseFailureException;
import org.theseed.io.TabbedLineReader;
import org.theseed.reports.ThrProductionFormatter;
import org.theseed.samples.SampleId;

/**
 * This command formats the threonine production data.  The data comes in on the standard input, in tab-delimited
 * format.  The following columns are used
 *
 * 	sample			sample ID
 * 	thr_production	threonine production
 *  bad				"Y" for a row with bad data, "?" for a row with questionable data, else empty
 *
 * There is also a required "choices.tbl" file that describes the labels for each of the fields in the strain name.
 * The records in this file correspond to the fields in the strain ID.  The two last records represent the inserted and
 * deleted genes, which get special treatment.  The possible field values are listed in comma-separated form.  Values of "0" or "000"
 * are treated as empty cases.
 *
 * The positional parameter is the name of the output directory.
 *
 * The command-line options are as follows.
 *
 * --input		input file (if not the standard input)
 * --choices	name of the choices file (default is "choices.tbl" in the current directory)
 * --format		format for the output
 * --minHours	minimum time point to include (default 0)
 * --maxHours	maximum time point to include (default 24)
 * --min		minimum production; only production values strictly greater than this will be output; the default is -1
 * --max		maximum production; only production values strictly less than this will be output; the default is 100
 * --pure		skip questionable results
 * --prod		name of production column to use; the default is "thr_production"
 * --meta		list of meta-data column labels to use (multiple may be specified)
 * --label		label to use for the production column; the default is "production"
 * --limited	comma-delimited list of strains IDs to include (default is include all)
 * --run		if specified, the a comma-delimited list of run names to include; run names are in the column "first_run"
 * 				(default is to not check for runs)
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
    /** include set */
    private Set<String> strainsToKeep;
    /** run set */
    private Set<String> runsToKeep;

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

    /** minimum time point */
    @Option(name = "--minHours", usage = "minimum time point")
    private double minHours;

    /** maximum time point */
    @Option(name = "--maxHours", usage = "maximum time point")
    private double maxHours;

    /** minimum acceptable production */
    @Option(name = "--min", usage = "minimum bound on the production value (exclusive)")
    private double minBound;

    /** maximum acceptable production */
    @Option(name = "--max", usage = "maximum bound on the production value (exclusive)")
    private double maxBound;

    /** if specified, questionable results will be skipped */
    @Option(name = "--pure", usage = "suppress output of questionable results")
    private boolean pureFlag;

    /** name of column containing production value to use */
    @Option(name = "--prod", metaVar = "thr_rate", usage = "name of threonine production column from input file")
    private String prodName;

    /** label to give to column containing production value */
    @Option(name = "--label", metaVar = "thr_prod", usage = "label to put on production output column")
    private String prodLabel;

    /** list of additional meta-data columns to output */
    @Option(name = "--meta", metaVar = "growth", usage = "list of meta-data columns to specify")
    private List<String> metaCols;

    /** if specified, output will be limited to the specified strains */
    @Option(name = "--limited", usage = "comma-delimited list of strain IDs to include (default includes all)")
    private String limitStrains;

    /** if specified, comma-delimited list of runs to keep */
    @Option(name = "--run", metaVar = "run1,run2,...", usage = "comma-delimited list of runs to include (default includes all)")
    private String runList;

    /** output file */
    @Argument(index = 0, metaVar = "outFile.csv", usage = "output file")
    private File outFile;

    @Override
    protected void setDefaults() {
        this.inFile = null;
        this.choiceFile = new File(System.getProperty("user.dir"), "choices.tbl");
        this.format = ThrProductionFormatter.Type.TABLE;
        this.minHours = 0.0;
        this.maxHours = 24.0;
        this.minBound = -1.0;
        this.maxBound = 100.0;
        this.pureFlag = false;
        this.prodName = "thr_production";
        this.metaCols = new ArrayList<String>();
        this.limitStrains = null;
        this.prodLabel = "production";
        this.runList = null;
    }

    @Override
    protected boolean validateParms() throws IOException, ParseFailureException {
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
        if (this.minBound >= this.maxBound)
            throw new ParseFailureException("Minimum bound must be strictly less than maximum bound, since both are exclusive.");
        if (this.minHours > this.maxHours)
            throw new ParseFailureException("Minimum time point cannot be greater than maximum time point.");
        log.info("Threonine production column name is {}.", this.prodName);
        // Process the strains to keep.
        this.strainsToKeep = null;
        if (this.limitStrains != null) {
            this.strainsToKeep = Arrays.stream(StringUtils.split(this.limitStrains, ',')).collect(Collectors.toSet());
            log.info("Strains will be limited to: {}.", StringUtils.join(this.strainsToKeep, ", "));
        }
        // Process the runs to keep.
        this.runsToKeep = null;
        if (this.runList != null) {
            this.runsToKeep = Arrays.stream(StringUtils.split(this.runList, ',')).collect(Collectors.toSet());
            log.info("Runs will be limited to: {}.", StringUtils.join(this.runsToKeep, ". "));
        }
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Open the output object and the input file.
        try (TabbedLineReader reader = new TabbedLineReader(this.reader);
                ThrProductionFormatter writer = this.format.create(this.outFile, this.metaCols)) {
            // Locate the important input columns.
            int sampleCol = reader.findField("sample");
            int prodCol = reader.findField(this.prodName);
            int[] metaColNums = new int[this.metaCols.size()];
            for (int i = 0; i < metaColNums.length; i++)
                metaColNums[i] = reader.findField(this.metaCols.get(i));
            int badCol = reader.findField("bad");
            int runCol = -1;
            if (this.runsToKeep != null)
                runCol = reader.findField("first_run");
            // Set the production column label.
            writer.setProdName(this.prodLabel);
            // Start the report.
            writer.initialize(this.choiceFile);
            log.info("Reading input file.");
            int count = 0;
            int badCount = 0;
            int skipCount = 0;
            int boundCount = 0;
            int timeCount = 0;
            int excludeCount = 0;
            // Loop through the input.
            for (TabbedLineReader.Line line : reader) {
                String badFlag = line.get(badCol);
                if (badFlag.equals("Y"))
                    badCount++;
                else if (this.pureFlag && badFlag.equals("?"))
                    skipCount++;
                else if (runCol >= 0 && ! this.runsToKeep.contains(line.get(runCol)))
                    excludeCount++;
                else {
                    String sampleId = line.get(sampleCol);
                    SampleId sample = new SampleId(sampleId);
                    // Check for strain limitations.
                    if (this.strainsToKeep != null && ! this.strainsToKeep.contains(sample.getFragment(0)))
                        excludeCount++;
                    else {
                        double production = line.getDouble(prodCol);
                        double[] metaVals = Arrays.stream(metaColNums).mapToDouble(i -> line.getDouble(i)).toArray();
                        // Check for the bounds and the 24-hour filter.
                        if (production <= this.minBound || production >= this.maxBound)
                            boundCount++;
                        else if (! this.checkTime(sample))
                            timeCount++;
                        else {
                            // Here we have a good sample.
                            writer.writeSample(sample, production, metaVals);
                            count++;
                        }
                    }
                }
            }
            log.info("{} samples written, {} questionable samples skipped, {} bad samples skipped, {} outside the production bounds, {} were filtered by time point, {} excluded.",
                    count, skipCount, badCount, boundCount, timeCount, excludeCount);
        }
    }

    /**
     * @return TRUE if sample is in range, else FALSE
     *
     * @param sample	ID of the sample
     */
    private boolean checkTime(SampleId sample) {
        return (sample.getTimePoint() <= this.maxHours && sample.getTimePoint() >= this.minHours);
    }

}
