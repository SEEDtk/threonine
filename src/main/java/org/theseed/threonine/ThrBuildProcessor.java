package org.theseed.threonine;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

import org.kohsuke.args4j.Argument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.utils.BaseProcessor;
import org.theseed.utils.ParseFailureException;

/**
 * This command reads experiments from a directory and creates a master
 * production file that can be processed by the "thrfix" command.  Each
 * experiment is in its own subdirectory that is titled using the experiment
 * ID.  The following files must be present.
 *
 * "key.txt" describes the layout of the main 96-well plate.  The file is
 * tab-delimited without headers, and divided into three sections.  The first
 * section describes what is in each column (number portion of the well ID).
 * The second section describes what is in each row (letter portion of the well ID).
 * The third section contains individual well overrides.  For the non-overridden
 * wells, the strain string consists of the column string followed by the row string.
 *
 * "prod.txt" describes the layout of the full production plate.  The file is
 * tab-delimited without headers, and divided into two sections.  The first section
 * contains the well ID and time point for each production location.  The second
 * section contains the production in mg/L in each plate position.
 *
 * The growth numbers are in separate files for each time point.  These files are
 * comma-delimited, with headers.  The first column contains a well ID and the third
 * contains the OD/600 measurement.  Each row's measurements must be corrected by
 * subtracting the measurement in the last column of the row.  The file name will be
 * of the form "set XX DD hrs XXXXXX.csv", where "DD" is the time point.
 *
 * The positional parameters are the name of the input directory and the name of
 * the output file.  The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	show more frequent log messages
 *
 * @author Bruce Parrello
 *
 */
public class ThrBuildProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(ThrBuildProcessor.class);

    // COMMAND-LINE OPTIONS

    /** name of the input directory */
    @Argument(index = 0, metaVar = "inDir", usage = "input directory of experiments",
            required = true)
    private File inDir;

    /** name of the output file */
    @Argument(index = 1, metaVar = "outFile", usage = "output file", required = true)
    private File outFile;

    /**
     * This file filter only returns directories containing a "key.txt" file.
     */
    public static class ExpDirFilter implements FileFilter {

        @Override
        public boolean accept(File pathname) {
            File keyFile = new File(pathname, "key.txt");
            return keyFile.exists();
        }

    }


    @Override
    protected void setDefaults() {
    }

    @Override
    protected boolean validateParms() throws IOException, ParseFailureException {
        if (! this.inDir.isDirectory())
            throw new FileNotFoundException("Input directory " + this.inDir +
                    " not found or invalid.");
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Open the output file and write the header line.  Note the header names are a bit goofy
        // and represent a need to be compatible with legacy data files.
        try (PrintWriter writer = new PrintWriter(outFile)) {
            writer.println("strain_lower\tiptg\ttime\tThr\tGrowth\tSuspect\texperiment\tSample_y");
            // Get the experiment directories.
            File[] expDirs = this.inDir.listFiles(new ExpDirFilter());
            log.info("{} experiments found in {}.", expDirs.length, this.inDir);
            // Loop through them.
            for (File expDir : expDirs) {
                // Create the experiment object.  The ID is the base directory name.
                String expId = expDir.getName();
                ExperimentData exp = new ExperimentData(expId);
                // Read the layout file.
                File keyFile = new File(expDir, "key.txt");
                exp.readKeyFile(keyFile);
                // Read the growth files.
                Map<Double, File> growthMap = ExperimentData.getGrowthFiles(expDir);
                for (Map.Entry<Double, File> growthEntry : growthMap.entrySet()) {
                    exp.readGrowthFile(growthEntry.getValue(), growthEntry.getKey());
                }
                // Read the production file.
                File prodFile = new File(expDir, "prod.txt");
                exp.readProdFile(prodFile);
                // Write the experiment results to the output file.
                log.info("Writing results from experiment {}.", expId);
                for (ExperimentData.Result result : exp) {
                    writer.format("%s\t%s\t%4.1f\t%8.6f\t%8.6f\t0\t%s\t%s%n",
                            result.getStrain(), (result.isIptg() ? "TRUE" : "FALSE"),
                            result.getTimePoint(), result.getProduction(),
                            result.getGrowth(), expId, result.getWell());
                }
            }
            // Insure all the output lines are written.
            writer.flush();
        }
    }

}
