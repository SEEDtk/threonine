/**
 *
 */
package org.theseed.reports;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.MarkerFile;

/**
 * This formatter creates a full machine learning directory for the "dl4j.jfx" application, but as a classification
 * problem, rather than a regression problem.  This includes a header-only "training.tbl" file, a "data.tbl" file with
 * the full training/testing set, and a "labels.txt" file with the classification labels (none, low, high).
 *
 * @author Bruce Parrello
 *
 */
public class ClassThrProductionFormatter extends TextThrProductionFormatter {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(ClassThrProductionFormatter.class);
    /** target directory */
    private File outDir;

    public ClassThrProductionFormatter(File outDir) throws IOException {
        this.outDir = outDir;
        this.initDirectory(outDir);
        // Add the randomforest marker.
        MarkerFile.write(new File(outDir, "decider.txt"), "RandomForest");
        // Create the label file.
        File labelFile = new File(outDir, "labels.txt");
        try (PrintWriter labelStream = new PrintWriter(labelFile)) {
            labelStream.println("None");
            labelStream.println("Low");
            labelStream.println("High");
        }
    }

    @Override
    protected void writeLine(DataLine line) {
        this.writer.println(line.toClassString());
    }

    @Override
    protected void registerHeader(String header) {
        header = header + "\t" + this.getProdName() + "_level";
        File trainFile = new File(this.outDir, "training.tbl");
        log.info("Header stored in {}.", trainFile);
        MarkerFile.write(trainFile, header);
        this.writer.println(header);
    }

}
