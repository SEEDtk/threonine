/**
 *
 */
package org.theseed.reports;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.MarkerFile;

/**
 * This formatter creates a full machine learning directory for the "dl4j.jfx" application.  This includes a header-only
 * "training.tbl" file, a "data.tbl" file with the full training/testing set, and a "labels.txt" with the "production" label.
 *
 * @author Bruce Parrello
 *
 */
public class DirThrProductionFormatter extends TextThrProductionFormatter {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(DirThrProductionFormatter.class);
    /** target directory */
    private File outDir;


    /**
     * Set up the output directory for this production file.
     *
     * @param outDir	target output directory
     *
     * @throws IOException
     */
    public DirThrProductionFormatter(File outDir) throws IOException {
        this.outDir = outDir;
        if (! outDir.isDirectory()) {
            // Here we must create the directory.
            log.info("Creating output directory {}.", outDir);
            FileUtils.forceMkdir(outDir);
        } else {
            // Here we must erase the current directory contents.
            log.info("Erasing output directory {}.", outDir);
            FileUtils.cleanDirectory(outDir);
        }
        // Create the label file.
        File labelFile = new File(outDir, "labels.txt");
        MarkerFile.write(labelFile, "production");
        // Set up the main output file.
        File dataFile = new File(outDir, "data.tbl");
        log.info("Training/testing set will be output to {}.", dataFile);
        this.setupDataFile(dataFile, "\t");
    }

    @Override
    protected void registerHeader(String header) {
        File trainFile = new File(this.outDir, "training.tbl");
        log.info("Header stored in {}.", trainFile);
        MarkerFile.write(trainFile, header);
    }

}
