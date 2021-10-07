/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.TabbedLineReader;
import org.theseed.reports.MeanComputer;
import org.theseed.samples.SampleId;
import org.theseed.samples.WellDescriptor;
import org.theseed.utils.BaseReportProcessor;
import org.theseed.utils.ParseFailureException;

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
 * --mean	type of mean to use (TRIMEAN, MAX, MIDDLE, SIGMA1, SIGMA2)
 *
 * @author Bruce Parrello
 *
 */
public class SugarMergeProcessor extends BaseReportProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(SugarMergeProcessor.class);
    /** array of sugar data column titles */
    private String[] sugarTitles;
    /** computation engine for the mean */
    private MeanComputer computer;

    // COMMAND-LINE OPTIONS

    /** type of mean computation to perform */
    @Option(name = "--mean", usage = "type of mean to compute when merging values")
    private MeanComputer.Type meanType;

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
    }

    @Override
    protected void validateReporterParms() throws IOException, ParseFailureException {
        if (! this.sugarFile.canRead())
            throw new FileNotFoundException("Sugar data file " + this.sugarFile + " is not found or unreadable.");
        if (! this.bigProdFile.canRead())
            throw new FileNotFoundException("Big production table file " + this.bigProdFile +
                    " is not found or unreadable.");
        this.computer = this.meanType.create();
    }

    @Override
    protected void runReporter(PrintWriter writer) throws Exception {
        // Loop through the sugar data, building a map from experiment wells to sugar arrays.
        Map<WellDescriptor, double[]> sugarMap = new HashMap<WellDescriptor, double[]>(3000);
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
                // Get the time point and the origin, then form the well descriptor.
                String origin = line.get(originCol);
                SampleId sample = new SampleId(line.get(sampleCol));
                double timePoint = sample.getTimePoint();
                WellDescriptor well = new WellDescriptor(origin, timePoint);
                // Get the array of sugar values and store them in the map.
                double[] values = IntStream.range(prodCol + 1, endCol)
                        .mapToDouble(i -> line.getDouble(i)).toArray();
                sugarMap.put(well, values);
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
            // This will be used as the output array.  For each sugar value, we keep a list of
            // Doubles.  These lists are then fed to the mean computer one at a time.
            List<List<Double>> listArray = IntStream.range(0, this.sugarTitles.length)
                    .mapToObj(i -> new ArrayList<Double>()).collect(Collectors.toList());
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
                // Clear the list array.
                listArray.forEach(x -> x.clear());
                // Denote we haven't found any origins with sugar data.
                int found = 0;
                // Loop through the origins, filling the lists in the list array.
                for (String origin : origins) {
                    WellDescriptor well = new WellDescriptor(origin, timePoint);
                    double[] values = sugarMap.get(well);
                    if (values != null) {
                        IntStream.range(0, this.sugarTitles.length).forEach(i -> listArray.get(i).add(values[i]));
                        found++;
                    }
                }
                // If we found an origin, then this is an output line.  Compute the means and format
                // them for output.
                if (found > 0) {
                    String means = IntStream.range(0, this.sugarTitles.length)
                            .mapToDouble(i -> this.computer.goodMean(listArray.get(i)))
                            .mapToObj(f -> String.format("\t%8.4f", f)).collect(Collectors.joining());
                    writer.println(line.toString() + means);
                    prodKept++;
                    wellsUsed += found;
                }
            }
            log.info("{} production records read, {} kept, {} well values used.", prodIn, prodKept, wellsUsed);
        }
    }

}
