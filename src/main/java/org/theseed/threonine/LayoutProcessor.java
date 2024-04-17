/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.TreeMap;

import org.kohsuke.args4j.Argument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.basic.BaseReportProcessor;
import org.theseed.basic.ParseFailureException;
import org.theseed.experiments.ExperimentData;
import org.theseed.experiments.ExperimentGroup;
import org.theseed.experiments.MultiExperimentGroup;
import org.theseed.reports.NaturalSort;

/**
 * This command takes as input a layout file and produces a simple report listing all the wells along
 * with the content of each.
 *
 * The positional parameter is the name of an experiment directory (currently only the multi-experiment group
 * format is supported).  The report will be produced on the standard output.
 *
 * The following command-line options are supported:
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -o	output file (if not STDOUT)
 *
 * @author Bruce Parrello
 *
 */
public class LayoutProcessor extends BaseReportProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(LayoutProcessor.class);
    /** experiment group for the input directory */
    private ExperimentGroup expGroup;

    // COMMAND-LINE OPTIONS

    /** input experiment group directory */
    @Argument(index = 0, metaVar = "expDir", usage = "experiment group directory to process")
    private File expDir;

    @Override
    protected void setReporterDefaults() {
    }

    @Override
    protected void validateReporterParms() throws IOException, ParseFailureException {
        if (! expDir.isDirectory())
            throw new FileNotFoundException("Experiment group directory " + this.expDir + " is not found or invalid.");
    }

    @Override
    protected void runReporter(PrintWriter writer) throws Exception {
        log.info("Reading layout information from {}.", this.expDir);
        this.expGroup = new MultiExperimentGroup(this.expDir, "main");
        this.expGroup.processFiles();
        // Now we produce the output.  We loop through the plates, producing output.
        writer.println("plate\twell\tstrain");
        for (ExperimentData plateData : this.expGroup) {
            String plateId = plateData.getId();
            log.info("Writing layout for plate {}.", plateId);
            // Store the wells.
            Map<String, String> output = new TreeMap<String, String>(new NaturalSort());
            for (Map.Entry<String, String> wellEntry : plateData.getLayout())
                output.put(wellEntry.getKey(),
                        plateId + "\t" + wellEntry.getKey() + "\t" + wellEntry.getValue());
            output.values().stream().forEach(x -> writer.println(x));
        }
    }

}
