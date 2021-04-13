/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import org.kohsuke.args4j.Argument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.experiments.ExperimentData;
import org.theseed.experiments.ExperimentGroup;
import org.theseed.experiments.MultiExperimentGroup;
import org.theseed.experiments.SetExperimentGroup;
import org.theseed.utils.BaseProcessor;
import org.theseed.utils.ParseFailureException;

/**
 * This script processes a threonine experiment directory.  Each subdirectory has as its name an experiment
 * group ID and contains all the files describing the experiment. There are two types of subdirectories, the
 * MULTI type that contains multiple layout files in Word documents, and the SET type that contains a single
 * layout file in an Excel spreadsheet.  The type of directory is indicated by a marker file in the directory
 * with the name "SET" or "MULTI".
 *
 * An output file suitable for processing by ThrFixProcessor is produced
 *
 * The positional parameters are the input directory and the output file name.
 *
 * The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 *
 * @author Bruce Parrello
 *
 */
public class ThrSetBuildProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(ThrSetBuildProcessor.class);

    // COMMAND-LINE OPTIONS

    /** input directory name */
    @Argument(index = 0, metaVar = "inDir", usage = "input directory name")
    private File inDir;

    /** output file name */
    @Argument(index = 1, metaVar = "outFile.tbl", usage = "output file name")
    private File outFile;

    @Override
    protected void setDefaults() {
    }

    @Override
    protected boolean validateParms() throws IOException, ParseFailureException {
        // Verify that the input directory exists.
        if (! this.inDir.isDirectory())
            throw new FileNotFoundException("Input directory " + this.inDir + " not found or invalid.");
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Get a list of the subdirectories.
        File[] subDirs = this.inDir.listFiles(File::isDirectory);
        log.info("{} subdirectories found in {}.", this.inDir);
        // Start the output file.
        try (PrintWriter writer = new PrintWriter(outFile)) {
            int valueCount = 0;
            int badCount = 0;
            int skipCount = 0;
            // Write the header.  Note the odd titles are due to a need to be compatible with legacy files.
            writer.println("strain_lower\tiptg\ttime\tThr\tGrowth\tSuspect\texperiment\tSample_y");
            // Loop through the subdirectories.
            for (File subDir : subDirs) {
                // Determine the directory type.
                File multiMarker = new File(subDir, "MULTI");
                File setMarker = new File(subDir, "SET");
                ExperimentGroup group = null;
                if (multiMarker.exists())
                    group = new MultiExperimentGroup(subDir, subDir.getName());
                else if (setMarker.exists())
                    group = new SetExperimentGroup(subDir, subDir.getName());
                if (group == null)
                    log.info("Subdirectory {} does not appear to contain an experiment group:  no type marker found.");
                else {
                    // Process the files in the subdirectory.
                    log.info("Processing experiment group {}.", group.getExpID());
                    group.processFiles();
                    // Loop through the experiments.
                    for (ExperimentData exp : group) {
                        String expId = exp.getId();
                        log.info("Writing results from experiment group {}.", expId);
                        for (ExperimentData.Result result : exp) {
                            if (! result.isComplete()) {
                                log.info("Skipping incomplete result {}.", result.getWell());
                                skipCount++;
                            } else {
                                String badFlag = "";
                                if (result.isSuspect()) {
                                    badFlag = "Y";
                                    badCount++;
                                }
                                writer.format("%s\t%s\t%4.1f\t%8.6f\t%8.6f\t%s\t%s\t%s%n",
                                        result.getStrain(), (result.isIptg() ? "TRUE" : "FALSE"),
                                        result.getTimePoint(), result.getProduction(),
                                        result.getGrowth(), badFlag, expId, result.getWell());
                                valueCount++;
                            }
                        }
                    }
                    log.info("{} output data lines written.  {} bad wells, {} incomplete wells.", valueCount, badCount, skipCount);
                }
            }
        }
    }

}
