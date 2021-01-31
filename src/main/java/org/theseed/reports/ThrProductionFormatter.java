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

import org.theseed.proteins.SampleId;

/**
 * This is the base class for all the threonine-production formatting methods.
 *
 * @author Bruce Parrello
 *
 */
public abstract class ThrProductionFormatter extends ThrSampleFormatter implements AutoCloseable {

    // FIELDS
    /** output file stream */
    private OutputStream outStream;

    /**
     * Enum for the different output types
     */
    public static enum Type {
        CSV, TABLE, DIR;

        public ThrProductionFormatter create(File outFile) throws IOException {
            ThrProductionFormatter retVal = null;
            switch (this) {
            case CSV :
                retVal = new TextThrProductionFormatter(outFile, ",");
                break;
            case TABLE :
                retVal = new TextThrProductionFormatter(outFile, "\t");
                break;
            case DIR :
                retVal = new DirThrProductionFormatter(outFile);
            }
            return retVal;
        }

    }

    /**
     * Set up the formatter for a specified output stream.
     *
     * @param output	output stream
     *
     * @throws FileNotFoundException
     */
    protected void setOutput(File outFile) throws FileNotFoundException {
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
        setupChoices(choiceFile);
        this.openReport();
    }

    /**
     * Start the report output.
     */
    protected abstract void openReport();

    /**
     * Write a sample to the output.
     */
    public abstract void writeSample(SampleId sample, double production, double density);

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
