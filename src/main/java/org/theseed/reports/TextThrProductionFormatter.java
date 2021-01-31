/**
 *
 */
package org.theseed.reports;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import org.apache.commons.text.TextStringBuilder;
import org.theseed.io.Shuffler;
import org.theseed.proteins.SampleId;

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
    private PrintWriter writer;
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
    private class DataLine {
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
    public void writeSample(SampleId sample, double production, double density) {
        double[] data = this.parseSample(sample);
        int n = data.length + 2;
        if (this.lastValue == null) {
            // First time through, so initialize the tracking system.
            this.lastValue = new double[n];
            this.keepCol = new boolean[n];
            for (int i = 0; i < data.length; i++) {
                this.lastValue[i] = data[i];
                this.keepCol[i] = false;
            }
            // We always keep the production and density.
            this.keepCol[n - 2] = true;
            this.keepCol[n - 1] = true;
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
        this.lastValue[n - 2] = production;
        this.lastValue[n - 1] = density;
        // Add this row to the output buffer.
        this.buffer.add(new DataLine(sample.toString(), this.lastValue));
    }

    @Override
    protected void closeReport() {
        // Write the header line.
        this.stringBuffer.clear().append("sample_id");
        String[] titles = this.getTitles();
        for (int i = 0; i < titles.length; i++) {
            if (this.keepCol[i])
                this.stringBuffer.append(delim).append(titles[i]);
        }
        this.stringBuffer.append(delim).append("production").append(delim).append("density");
        String header = this.stringBuffer.toString();
        this.registerHeader(header);
        this.writer.println(header);
        // Scramble the output.
        log.info("Shuffling data lines for output.");
        this.buffer.shuffle(this.buffer.size());
        // Spool it out.
        log.info("Unspooling data lines.");
        for (DataLine line : this.buffer)
            this.writer.println(line.toString());
        this.writer.close();
    }

    /**
     * Make sure the subclass knows what the header looks like.
     *
     * @param header	proposed header line
     */
    protected void registerHeader(String header) {
    }

}
