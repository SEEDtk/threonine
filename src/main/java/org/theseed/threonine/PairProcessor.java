/**
 *
 */
package org.theseed.threonine;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.basic.ParseFailureException;
import org.theseed.io.TabbedLineReader;
import org.theseed.samples.SampleId;
import org.theseed.utils.BasePipeProcessor;

/**
 *
 * This report displays the performance of gene-modification pairs.  A generic sample ID will be provided as an option,
 * and only samples matching the generic sample ID will be processed.  For each gene-modification pair found, the two
 * genes, the sample ID, and the threonine output level will be written to the output report.
 *
 * The standard input should contain the big production table.  The report will be written to the standard output.  The
 * command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -i	big production table input file (if not STDIN)
 * -o	output file for report (if not STDOUT)
 *
 * --filter		generic sample ID for filtering input (default "7_0_TA1_C_X_X_X_X_24_M1")
 * --min		minimum acceptable threonine production level (default 1.5)
 *
 * @author Bruce Parrello
 *
 */
public class PairProcessor extends BasePipeProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(PairProcessor.class);
    /** sample ID column index */
    private int sampleIdIdx;
    /** production level column index */
    private int prodIdx;
    /** sample ID filter */
    private SampleId filter;

    // COMMAND-LINE OPTIONS

    /** sample ID filter */
    @Option(name = "--filter", metaVar = "M_0_X_X_asdO_X_X_I_24_M1", usage = "sample ID pattern used to filter input (X used for wild card)")
    private String filterString;

    /** minimum threonine production level */
    @Option(name = "--min", metaVar = "1.2", usage = "minimum threonine production level used to filter input")
    private double minLevel;

    @Override
    protected void setPipeDefaults() {
        this.filterString = "7_0_TA1_C_X_X_X_X_24_M1";
        this.minLevel = 1.5;
    }

    @Override
    protected void validatePipeInput(TabbedLineReader inputStream) throws IOException {
        // Verify we have the expected column headers.
        this.sampleIdIdx = inputStream.findField("sample");
        this.prodIdx = inputStream.findField("thr_production");
    }

    @Override
    protected void validatePipeParms() throws IOException, ParseFailureException {
        // Verify the sample ID.
        this.filter = new SampleId(this.filterString);
        log.info("Saved sample ID filter is {}.", this.filter.toString());
        // Verify the minimum threshold.
        if (this.minLevel < 0.0)
            throw new ParseFailureException("Minimum level must be >= 0.");
    }

    @Override
    protected void runPipeline(TabbedLineReader inputStream, PrintWriter writer) throws Exception {
        // Write the output report heading.
        writer.println("sample_id\tgene_1\tgene_2\tprod_level");
        // Loop through the input lines.
        int lineCount = 0;
        int levelSkip = 0;
        int idSkip = 0;
        int keptCount = 0;
        for (var line : inputStream) {
            lineCount++;
            // Get the sample ID and production level.
            SampleId sample = new SampleId(line.get(this.sampleIdIdx));
            double level = line.getDouble(this.prodIdx);
            // Apply the filters.
            if (! sample.matches(this.filter))
                idSkip++;
            else if (level < this.minLevel)
                levelSkip++;
            else {
                // Now we need to insure there are only two gene modifications.
                var deletes = sample.getDeletes();
                if (deletes.size() <= 2) {
                    var inserts = sample.getInserts();
                    if (inserts.size() + deletes.size() == 2) {
                        // Here we have a sample to output.  Extract the two genes.
                        List<String> genes = new ArrayList<String>(2);
                        genes.addAll(inserts);
                        deletes.stream().forEach(x -> genes.add("D" + x));
                        keptCount++;
                        writer.format("%s\t%s\t%s\t%8.4f%n", sample.toString(), genes.get(0), genes.get(1), level);
                    }
                }
            }
        }
        log.info("{} lines read, {} filtered by ID, {} filtered by level, {} kept.", lineCount, idSkip, levelSkip, keptCount);
    }

}
