/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.kohsuke.args4j.Argument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.rna.RnaData;
import org.theseed.rna.RnaData.Weight;
import org.theseed.rna.RnaFeatureData;
import org.theseed.utils.BaseProcessor;
import org.theseed.utils.ParseFailureException;

/**
 * This command searches through multiple RNA seq databases to find genes with a relatively constant expression
 * level.  The range of expression levels will be computed for each gene, and a report written
 * on the ones with the least variation between minimum and maximum.
 *
 * The positional parameters are the names of the RNA seq databases to scan.  The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	show more detailed log messages
 *
 * @author Bruce Parrello
 *
 */
public class ConstantExpressionProcessor extends BaseProcessor {

    /**
     *
     */
    private static final int GENE_SIZE = 4500;
    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(ConstantExpressionProcessor.class);
    /** map of feature IDs to gene names */
    private Map<String, String> geneMap;
    /** map of feature IDs to expression level summaries */
    private Map<String, SummaryStatistics> statMap;

    // COMMAND-LINE OPTIONS

    /** RNA seq databases to scan */
    @Argument(index = 0, metaVar = "rnaDb1 rnaDb2 ...", usage = "RNA seq expression databases to scan", required = true)
    private List<File> rnaDBs;

    @Override
    protected void setDefaults() {
    }

    @Override
    protected boolean validateParms() throws IOException, ParseFailureException {
        // Verify that all the files are readable.
        for (File rnaDB : this.rnaDBs) {
            if (! rnaDB.canRead())
                throw new FileNotFoundException("RNA seq database " + rnaDB + " is not found or unreadable.");
        }
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Create the maps.
        this.geneMap = new HashMap<String, String>(GENE_SIZE);
        this.statMap = new HashMap<String, SummaryStatistics>(GENE_SIZE);
        // Loop through the RNA seq databases, filling the stat maps.
        int count = 0;
        for (File rnaDB : this.rnaDBs) {
            count++;
            log.info("Processing RNA seq database {} ({} of {}).", rnaDB, count, this.rnaDBs.size());
            RnaData data = RnaData.load(rnaDB);
            log.info("{} features and {} jobs found in database.", data.height(), data.size());
            // Loop through the rows.  Each row represents a feature.
            for (RnaData.Row row : data) {
                RnaFeatureData feat = row.getFeat();
                String fid = feat.getId();
                // Insure we know the feature's gene.
                if (! this.geneMap.containsKey(fid))
                    this.geneMap.put(fid, feat.getGene());
                // Get the feature's statistical accumulator.
                SummaryStatistics stats = this.statMap.computeIfAbsent(fid, k -> new SummaryStatistics());
                for (Weight weight : row.goodWeights())
                    stats.addValue(weight.getWeight());
            }
        }
        // Now we have accumulated all the data we need.  For each feature with more than one expression value,
        // output its statistics.
        System.out.println("fid\tgene\tmin\tmax\tmean\tsdev\tratio");
        for (Map.Entry<String, SummaryStatistics> fidStat : this.statMap.entrySet()) {
            // Only proceed if there was data for this gene.
            SummaryStatistics stats = fidStat.getValue();
            if (stats.getN() > 0) {
                // Get the gene name.
                String gene = this.geneMap.getOrDefault(fidStat.getKey(), "");
                // Compute the range ratio.
                double min = stats.getMin();
                double max = stats.getMax();
                String ratio = (min > 0.0 ? String.format("%6.4f", max / min) : "");
                // Print the statistics.
                System.out.format("%s\t%s\t%6.0f\t%6.0f\t%6.0f\t%6.0f\t%s%n",
                        fidStat.getKey(), gene, min, max, stats.getMean(),
                        stats.getStandardDeviation(), ratio);
            }
        }
    }

}
