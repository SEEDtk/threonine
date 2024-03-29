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
import java.util.List;

import org.theseed.samples.SampleId;

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
    /** name to give to production column */
    private String prodName;
    /** list of metadata column names */
    private List<String> metaColNames;

    /**
     * Construct this reporter.
     */
    public ThrProductionFormatter() {
        this.prodName = "production";
    }

    /**
     * Enum for the different output types
     */
    public static enum Type {
        CSV, TABLE, DIR, CLASS;

        public ThrProductionFormatter create(File outFile, List<String> metaCols) throws IOException {
            ThrProductionFormatter retVal = null;
            switch (this) {
            case CSV :
                retVal = new TextThrProductionFormatter(outFile, ",");
                break;
            case TABLE :
                retVal = new TextThrProductionFormatter(outFile, "\t");
                break;
            case CLASS :
                retVal = new ClassThrProductionFormatter(outFile);
                break;
            case DIR :
                retVal = new DirThrProductionFormatter(outFile);
            }
            retVal.saveMetaCols(metaCols);
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
     * Save the names of the metadata columns.
     * 
	 * @param metaCols	metadata column names, in order
	 */
	public void saveMetaCols(List<String> metaCols) {
		this.metaColNames = metaCols;
	}

	/**
     * Set the name for the production column.
     *
     * @param newProdName	new column name to use
     */
    public void setProdName(String newProdName) {
        this.prodName = newProdName;
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
    public abstract void writeSample(SampleId sample, double production, double[] metaVals);

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

    /**
     * @return the production column label
     */
    protected String getProdName() {
        return this.prodName;
    }

	/**
	 * @return the metaColNames
	 */
	public List<String> getMetaColNames() {
		return metaColNames;
	}

}
