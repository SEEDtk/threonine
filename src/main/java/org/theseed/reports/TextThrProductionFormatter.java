/**
 *
 */
package org.theseed.reports;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.stream.IntStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.TextStringBuilder;
import org.theseed.samples.SampleId;
import org.theseed.stats.Shuffler;
import org.theseed.threonine.Production;

/**
 * This produces a text version of the production table designed for machine learning.  It supports
 * both tab-delimited and comma-delimited formats.  Special processing is used to remove columns that
 * always have the same value.
 *
 * @author Bruce Parrello
 *
 */
public class TextThrProductionFormatter extends ThrProductionFormatter {

    // FIELDS
    /** field delimiter */
    private String delim;
    /** output writer */
    protected PrintWriter writer;
    /** queue of output strings */
    private Shuffler<DataLine> buffer;
    /** array containing last value for each column */
    private double[] lastValue;
    /** array of booleans-- TRUE indicates the column is multivalued */
    private boolean[] keepCol;
    /** buffer for building data lines */
    TextStringBuilder stringBuffer;

    /**
     * This class is used to store lines for later output.
     */
    protected class DataLine {
        private String label;
        private double[] data;

        /**
         * Construct a data line.
         *
         * @param label		label for the line
         * @param data		data columns for the line
         */
        public DataLine(String label, double[] data) {
            this.label = label;
            this.data = data.clone();
        }

        /**
         * @return the string representation of this line
         */
        public String toString() {
            stringBuffer.clear().append(this.label);
            for (int i = 0; i < data.length; i++) {
                if (keepCol[i])
                    stringBuffer.append(delim).append(Double.toString(this.data[i]));
            }
            return stringBuffer.toString();
        }

        /**
         * @return the classification representation of this line
         */
        public String toClassString() {
            stringBuffer.clear().append(this.label);
            for (int i = 0; i < data.length; i++) {
                if (keepCol[i])
                    stringBuffer.append(delim).append(Double.toString(this.data[i]));
            }
            stringBuffer.append(delim);
            double production = this.data[data.length - 1];
            stringBuffer.append(Production.getLevel(production));
            return stringBuffer.toString();
        }
    }

    /**
     * Default constructor for subclasses.
     */
    protected TextThrProductionFormatter() {
    }

    /**
     * Create a new production file formatter.
     *
     * @param outFile	output file
     * @param delim		field delimiter
     *
     * @throws FileNotFoundException
     */
    public TextThrProductionFormatter(File outFile, String delim) throws FileNotFoundException {
        setupDataFile(outFile, delim);
    }

    /**
     * Initialize the output file for this formatter.
     *
     * @param outFile	output file
     * @param delim		delimiter to use between fields (comma or tab)
     *
     * @throws FileNotFoundException
     */
    protected void setupDataFile(File outFile, String delim) throws FileNotFoundException {
        this.setOutput(outFile);
        this.writer = new PrintWriter(outFile);
        this.delim = delim;
        this.buffer = new Shuffler<DataLine>(4000);
        this.stringBuffer = new TextStringBuilder(200);
    }

    @Override
    protected void openReport() {
        this.keepCol = null;
        this.lastValue = null;
    }

    @Override
    public void writeSample(SampleId sample, double production, double[] metaVals) {
        double[] data = this.parseSample(sample);
        int n = data.length + 1 + metaVals.length;
        if (this.lastValue == null) {
            // First time through, so initialize the tracking system.
            this.lastValue = new double[n];
            this.keepCol = new boolean[n];
            for (int i = 0; i < data.length; i++) {
                this.lastValue[i] = data[i];
                this.keepCol[i] = false;
            }
            // We always keep the production and the metadata
            IntStream.range(data.length, n).forEach(i -> this.keepCol[i] = true);
        } else {
            // Not the first time through, so check for differences.
            for (int i = 0; i < data.length; i++) {
                if (this.lastValue[i] != data[i]) {
                    this.lastValue[i] = data[i];
                    this.keepCol[i] = true;
                }
            }
        }
        // Save the production and density.
        this.lastValue[n - 1] = production;
        IntStream.range(0, metaVals.length).forEach(i -> this.lastValue[i + data.length] = metaVals[i]);
        // Add this row to the output buffer.
        this.buffer.add(new DataLine(sample.toString(), this.lastValue));
    }

    @Override
    protected void closeReport() {
        // Onlt proceed if we have some data to output.
        if (this.keepCol != null) {
            // Suppress the derived columns.
            this.suppressDerived(this.keepCol);
            // Write the header line.
            this.stringBuffer.clear().append("sample_id");
            String[] titles = this.getTitles();
            for (int i = 0; i < titles.length; i++) {
                if (this.keepCol[i])
                    this.stringBuffer.append(delim).append(titles[i]);
            }
            String metaLabel = "";
            List<String> metaNames = this.getMetaColNames();
            if (! metaNames.isEmpty())
            	metaLabel = StringUtils.join(this.getMetaColNames(), delim) + delim;
            this.stringBuffer.append(delim).append(metaLabel).append(this.getProdName());
            String header = this.stringBuffer.toString();
            this.registerHeader(header);
            // Scramble the output.
            log.info("Shuffling data lines for output.");
            this.buffer.shuffle(this.buffer.size());
            // Spool it out.
            log.info("Unspooling data lines.");
            for (DataLine line : this.buffer)
                this.writeLine(line);
        }
        this.writer.close();
    }

    /**
     * Write out the current data line in the appropriate format.
     *
     * @param line		data line to write
     */
    protected void writeLine(DataLine line) {
        this.writer.println(line.toString());
    }

    /**
     * Make sure the subclass knows what the header looks like.
     *
     * @param header	proposed header line
     */
    protected void registerHeader(String header) {
        this.writer.println(header);
    }

    /**
     * Initialize the output directory for machine learning.
     *
     * @param outDir	output directory
     *
     * @throws IOException
     */
    protected void initDirectory(File outDir) throws IOException {
        if (! outDir.isDirectory()) {
            // Here we must create the directory.
            log.info("Creating output directory {}.", outDir);
            FileUtils.forceMkdir(outDir);
        } else {
            // Here we must erase the current directory contents.
            log.info("Erasing output directory {}.", outDir);
            FileUtils.cleanDirectory(outDir);
        }
        // Set up the main output file.
        File dataFile = new File(outDir, "data.tbl");
        log.info("Training/testing set will be output to {}.", dataFile);
        this.setupDataFile(dataFile, "\t");
    }

}
