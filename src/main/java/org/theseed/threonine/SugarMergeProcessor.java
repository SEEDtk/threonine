/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.basic.BaseReportProcessor;
import org.theseed.basic.ParseFailureException;
import org.theseed.io.TabbedLineReader;
import org.theseed.reports.MeanComputer;
import org.theseed.samples.SampleId;
import org.theseed.samples.WellDescriptor;
import org.theseed.sugar.SugarMerger;

/**
 * This command takes the output file from SugarUtilizationProcessor and merges it with the big production
 * table output by ThrFixProcessor.  The result is a big production table with additional output columns.
 * Only samples for which sugar data is present will be included in the output.
 *
 * The origin column of the big production table is used as the key to the merging process. The origin
 * is a comma-delimited list of well identifiers (plate:address).  Suspect results are indicated by
 * parentheses around the identifier.  We require an exact match so that suspect results are not
 * included.  The output sugar values will be an average of the input calculated using a MeanComputer
 * object.
 *
 * The positional parameters are the name of the sugar data file and the name of the big production
 * table.
 *
 * -h	display command-line usage
 * -v	show more frequent log messages
 * -o	output file (if not STDOUT)
 *
 * --mean		type of mean to use (TRIMEAN, MAX, MIDDLE, SIGMA1, SIGMA2)
 * --merge		sugar-merging strategy-- MAX or MEAN
 *
 * @author Bruce Parrello
 *
 */
public class SugarMergeProcessor extends BaseReportProcessor implements SugarMerger.IParms {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(SugarMergeProcessor.class);
    /** array of sugar data column titles */
    private String[] sugarTitles;
    /** computation engine for the mean */
    private SugarMerger computer;

    
    // COMMAND-LINE OPTIONS

    /** type of mean computation to perform */
    @Option(name = "--mean", usage = "type of mean to compute when merging values")
    private MeanComputer.Type meanType;
    
    /** type of sugar merging to perform */
    @Option(name = "--merge", usage = "method for merging sugar data")
    private SugarMerger.Type sugarType;

    /** input sugar data file */
    @Argument(index = 0, metaVar = "sugar.tbl", usage = "input sugar data file", required = true)
    private File sugarFile;

    /** input big production table */
    @Argument(index = 1, metaVar = "big_production_table.tbl", usage = "input big production table",
            required = true)
    private File bigProdFile;

    @Override
    protected void setReporterDefaults() {
        this.meanType = MeanComputer.Type.TRIMEAN;
        this.sugarType = SugarMerger.Type.MEAN;
    }

    @Override
    protected void validateReporterParms() throws IOException, ParseFailureException {
        if (! this.sugarFile.canRead())
            throw new FileNotFoundException("Sugar data file " + this.sugarFile + " is not found or unreadable.");
        if (! this.bigProdFile.canRead())
            throw new FileNotFoundException("Big production table file " + this.bigProdFile +
                    " is not found or unreadable.");
        this.computer = this.sugarType.create(this);
    }

    @Override
    protected void runReporter(PrintWriter writer) throws Exception {
        // Loop through the sugar data, building a map from experiment wells to sugar arrays.
        Map<WellDescriptor, SugarMerger.DataPoint> sugarMap = 
        		new HashMap<WellDescriptor, SugarMerger.DataPoint>(3000);
        int sugarIn = 0;
        log.info("Reading sugar data from {}.", this.sugarFile);
        try (TabbedLineReader sugarStream = new TabbedLineReader(this.sugarFile)) {
            int sampleCol = sugarStream.findField("sample_id");
            int originCol = sugarStream.findField("origin");
            int prodCol = sugarStream.findField("production");
            int endCol = sugarStream.findField("suspect");
            // Every column between the production column and "suspect" contains a
            // derived sugar data value.  We need to save the column titles.
            this.sugarTitles = Arrays.copyOfRange(sugarStream.getLabels(), prodCol + 1, endCol);
            log.info("{} sugar values per data line.", this.sugarTitles.length);
            // Now loop through the sugar data, filling the map.
            for (TabbedLineReader.Line line : sugarStream) {
                sugarIn++;
                // Verify that we are not suspect.
                if (! line.get(endCol).contentEquals("Y")) {
	                // Get the time point and the origin, then form the well descriptor.
	                String origin = line.get(originCol);
	                SampleId sample = new SampleId(line.get(sampleCol));
	                double timePoint = sample.getTimePoint();
	                WellDescriptor well = new WellDescriptor(origin, timePoint);
	                // Get the production value.
	                double prod = line.getDouble(prodCol);
	                // Get the array of sugar values and store them in the map.
	                double[] values = IntStream.range(prodCol + 1, endCol)
	                        .mapToDouble(i -> line.getDouble(i)).toArray();
	                sugarMap.put(well, new SugarMerger.DataPoint(prod, values));
                }
            }
            log.info("{} lines read, {} put in map.", sugarIn, sugarMap.size());
        }
        // Now we need to read the big production table.  For each sample, we compute each mean sugar value
        // from all the wells in the origin for which we have data.
        log.info("Analyzing big production table in {}.", this.bigProdFile);
        try (TabbedLineReader prodStream = new TabbedLineReader(this.bigProdFile)) {
            int sampleCol = prodStream.findField("sample");
            int originCol = prodStream.findField("origins");
            // These will track our progress.
            int prodIn = 0;
            int prodKept = 0;
            int wellsUsed = 0;
            // Write the output header.
            writer.println(prodStream.header() + "\t" + StringUtils.join(this.sugarTitles, "\t"));
            // Loop through the big production table.
            for (TabbedLineReader.Line line : prodStream) {
                prodIn++;
                // Get the time point and the list of origin wells.
                SampleId sample = new SampleId(line.get(sampleCol));
                double timePoint = sample.getTimePoint();
                // Get the origin list.
                String[] origins = StringUtils.splitByWholeSeparator(line.get(originCol), ", ");
                // Initialize the sugar merger.
                this.computer.clear();
                // Denote we haven't found any origins with sugar data.
                int found = 0;
                // Loop through the origins, filling the lists in the list array.
                for (String origin : origins) {
                    WellDescriptor well = new WellDescriptor(origin, timePoint);
                    SugarMerger.DataPoint values = sugarMap.get(well);
                    if (values != null) {
                        this.computer.merge(values);
                        found++;
                    }
                }
                // If we found an origin, then this is an output line.  Compute the means and format
                // them for output.
                if (found > 0) {
                	this.computer.compute();
                    String means = IntStream.range(0, this.sugarTitles.length)
                            .mapToDouble(i -> this.computer.get(i))
                            .mapToObj(f -> String.format("\t%8.4f", f)).collect(Collectors.joining());
                    writer.println(line.toString() + means);
                    prodKept++;
                    wellsUsed += found;
                }
            }
            log.info("{} production records read, {} kept, {} well values used.", prodIn, prodKept, wellsUsed);
        }
    }

	@Override
	public MeanComputer.Type getMeanType() {
		return this.meanType;
	}

}
