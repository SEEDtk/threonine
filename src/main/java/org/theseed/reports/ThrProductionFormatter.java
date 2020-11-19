/**
 *
 */
package org.theseed.reports;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.LineReader;
import org.theseed.threonine.SampleId;

/**
 * This is the base class for all the threonine-production formatting methods.
 *
 * @author Bruce Parrello
 *
 */
public abstract class ThrProductionFormatter implements AutoCloseable {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(ThrProductionFormatter.class);
    /** list of choice arrays */
    private List<String[]> choices;
    /** permissible delete values */
    private String[] deleteChoices;
    /** output file stream */
    private OutputStream outStream;
    /** number of output columns for the sample description */
    private int numCols;

    /**
     * Enum for the different output types
     */
    public static enum Type {
        CSV, TABLE;

        public ThrProductionFormatter create(File outFile) throws FileNotFoundException {
            ThrProductionFormatter retVal = null;
            switch (this) {
            case CSV :
                retVal = new TextThrProductionFormatter(outFile, ",");
                break;
            case TABLE :
                retVal = new TextThrProductionFormatter(outFile, "\t");
                break;
            }
            return retVal;
        }

    }

    /**
     * Create the formatter for a specified output stream.
     *
     * @param output	output stream
     *
     * @throws FileNotFoundException
     */
    public ThrProductionFormatter(File outFile) throws FileNotFoundException {
        this.outStream = new FileOutputStream(outFile);
    }

    /**
     * Initialize this object with the choices from the choice file.
     *
     * @param choiceFile	file containing the choice data
     *
     * @throws IOException
     */
    public void initialize(File choiceFile) throws IOException {
        int n = SampleId.numBaseFragments();
        this.choices = new ArrayList<String[]>(n);
        // This will hold the number of output columns required for a sample ID.  We start with 2, for the
        // time and the IPTG flag.
        this.numCols = 2;
        // Read through the choice records.
        try (LineReader reader = new LineReader(choiceFile)) {
            for (int i = 0; i < n; i++) {
                String line = reader.next();
                // Delete any present null case.
                line = line.substring(StringUtils.indexOfAnyBut(line, " ,0"));
                // Get all the choices.
                String[] choiceArray = this.parseChoiceLine(line);
                this.choices.add(choiceArray);
                log.info("{} choices for strain fragment {}.", choiceArray.length, i);
            }
            // Now process the deletes.
            String line = reader.next();
            this.deleteChoices = this.parseChoiceLine(line);
            log.info("{} deletable proteins.", this.deleteChoices.length);
        }
        log.info("{} total specification columns.", this.numCols);
        this.openReport();
    }

    /**
     * @return the choices present in a line from the choice file
     *
     * @param line	input line to parse
     */
    private String[] parseChoiceLine(String line) {
        String[] retVal = StringUtils.split(line, ", ");
        this.numCols += retVal.length;
        return retVal;
    }

    /**
     * @return an array of labels for the feature columns
     */
    protected String[] getTitles() {
        String[] retVal = new String[numCols];
        int outIdx = 0;
        for (String[] choiceA : this.choices) {
            System.arraycopy(choiceA, 0, retVal, outIdx, choiceA.length);
            outIdx += choiceA.length;
        }
        System.arraycopy(this.deleteChoices, 0, retVal, outIdx, this.deleteChoices.length);
        int n = outIdx + this.deleteChoices.length;
        for (int i = outIdx; i < n; i++)
            retVal[i] = "D" + retVal[i];
        outIdx = n;
        retVal[outIdx++] = "IPTG";
        retVal[outIdx++] = "time";
        return retVal;
    }

    /**
     * Start the report output.
     */
    protected abstract void openReport();

    /**
     * Write a sample to the output.
     */
    public abstract void writeSample(String sampleId, double production, double density);

    /**
     * @return an array of numbers representing the structure of a sample
     *
     * @param sampleId	ID of the sample in question
     */
    protected double[] parseSample(String sampleId) {
        double[] retVal = new double[numCols];
        Arrays.fill(retVal, 0.0);
        SampleId sample = new SampleId(sampleId);
        String[] parts = sample.getBaseFragments();
        // This will track the current output location.
        int outIdx = 0;
        // Fill in the 1-hots for the base fragments.
        for (int i = 0; i < parts.length; i++) {
            this.storeOneHot(this.choices.get(i), parts[i], retVal, outIdx);
            outIdx += this.choices.get(i).length;
        }
        // Fill in the 1-choices for the deletes.
        for (String prot : sample.getDeletes())
            this.storeOneHot(this.deleteChoices, prot, retVal, outIdx);
        outIdx += this.deleteChoices.length;
        // Store the IPTG flag.
        if (sample.isIPTG())
            retVal[outIdx] = 1.0;
        outIdx++;
        // Store the time.
        retVal[outIdx++] = sample.getTimePoint();
        return retVal;

    }

    /**
     * Store a one-hot indicator in a section of the output array.
     *
     * @param choiceA	array of choices
     * @param choice	string representing the choice to store
     * @param output	output array
     * @param outIdx	position in the output array of choice 0
     */
    private void storeOneHot(String[] choiceA, String choice, double[] output, int outIdx) {
        int idx = ArrayUtils.indexOf(choiceA, choice);
        if (idx >= 0)
            output[outIdx + idx] = 1.0;
    }

    /**
     * Close this report.
     */
    public void close() {
        log.info("Finishing report.");
        this.closeReport();
        try {
            this.outStream.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Finish the report output.
     */
    protected abstract void closeReport();

}
