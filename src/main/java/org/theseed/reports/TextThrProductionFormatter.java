/**
 *
 */
package org.theseed.reports;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.theseed.io.Shuffler;

/**
 * This produces a text version of the production table designed for machine learning.  It supports
 * both tab-delimited and comma-delimited formats.
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
    private Shuffler<String> buffer;

    /**
     * Create an new production file formatter.
     *
     * @param outFile	output file
     * @param delim		field delimiter
     *
     * @throws FileNotFoundException
     */
    public TextThrProductionFormatter(File outFile, String delim) throws FileNotFoundException {
        super(outFile);
        this.writer = new PrintWriter(outFile);
        this.delim = delim;
        this.buffer = new Shuffler<String>(4000);
    }

    @Override
    protected void openReport() {
        // Write the line headings.
        this.writer.println("sample_id" + this.delim
                + StringUtils.join(this.getTitles(), this.delim)
                + this.delim + "production" + this.delim + "density"
            );
    }

    @Override
    public void writeSample(String sampleId, double production, double density) {
        String specifications = Arrays.stream(this.parseSample(sampleId)).mapToObj(v -> Double.toString(v))
                .collect(Collectors.joining(this.delim));
        String output = sampleId + this.delim + specifications +
                this.delim + Double.toString(production) +
                this.delim + Double.toString(density);
        this.buffer.add(output);
    }

    @Override
    protected void closeReport() {
        // Scramble the output.
        log.info("Shuffling data lines for output.");
        this.buffer.shuffle(this.buffer.size());
        // Spool it out.
        log.info("Unspooling data lines.");
        for (String line : this.buffer)
            this.writer.println(line);
        this.writer.close();
    }

}
