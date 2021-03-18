/**
 *
 */
package org.theseed.threonine;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.theseed.rna.RnaData;
import org.theseed.samples.SampleId;
import org.theseed.utils.ParseFailureException;

/**
 * This command produces comparison reports on all the multi-sample sets of RNA expression data in a single RNA Seq database.
 * It will produce an individual report on each group followed by a summary report.
 *
 * The positional parameter is the name of the RNA seq database file.
 *
 * The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -n	number of outliers to display; the default is 10
 * -o	output file; the default is to output to STDOUT
 *
 * @author Bruce Parrello
 *
 */
public class RnaSeqRepsProcessor extends RnaSeqCompareBaseProcessor {

    @Override
    protected void setDefaults() {
        this.setCompareDefaults();
    }

    @Override
    protected boolean validateParms() throws IOException, ParseFailureException {
        // Load the RNA seq database.
        this.loadRnaData();
        // Compute the output stream.
        this.setupOutput();
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Separate all the samples into groups.
        List<List<String>> repGroups = computeRepGroups();
        log.info("{} groups with multiple representatives.", repGroups.size());
        try (PrintWriter writer = getWriter()) {
            // The statistical objects will be stored in here.
            SortedMap<String, SummaryStatistics> statList = new TreeMap<String, SummaryStatistics>();
            for (List<String> group : repGroups) {
                SampleId sample = new SampleId(group.get(0));
                String repName = sample.repBaseId();
                log.info("Processing {}-sample group for {}.", group.size(), repName);
                SummaryStatistics stats = this.compareSamples(group, writer);
                statList.put(repName, stats);
                writer.println();
            }
            // Write the summary report.
            log.info("Producing summary report.");
            writer.println();
            String header = String.format("%-40s %14s %14s %14s", "Group", "Mean", "Std Dev", "Max");
            String dashes = StringUtils.repeat('-', header.length());
            writer.println(header);
            writer.println(dashes);
            for (Map.Entry<String, SummaryStatistics> entry : statList.entrySet()) {
                SummaryStatistics stats = entry.getValue();
                writer.format("%-40s %14.2f %14.2f %14.2f%n", entry.getKey(), stats.getMean(),
                        stats.getStandardDeviation(), stats.getMax());
            }
            log.info("All done. {} groups output.", statList.size());
        }
    }

    /**
     * Find all the samples with multiple representatives.
     *
     * @return the list of multiple-sample groups
     */
    private List<List<String>> computeRepGroups() {
        SortedMap<String, List<String>> groups = new TreeMap<String, List<String>>();
        int count = 0;
        for (RnaData.JobData sampleJob : this.getJobs()) {
            String sampleId = sampleJob.getName();
            String baseSampleId = new SampleId(sampleId).repBaseId();
            List<String> group = groups.computeIfAbsent(baseSampleId, x -> new ArrayList<String>(5));
            group.add(sampleId);
            count++;
        }
        log.info("{} groups found for {} samples.", groups.size(), count);
        // Select the groups with multiple samples.
        List<List<String>> repGroups = groups.values().stream().filter(x -> (x.size() > 1)).collect(Collectors.toList());
        return repGroups;
    }

}
